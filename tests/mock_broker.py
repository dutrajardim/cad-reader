"""Testes opcionais: criam o próprio broker local e executam o JAR Java.
Não acessam o CAD real. Execute após mvn package: python3 tests/mock_broker.py
"""
import base64
import pathlib
import re
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
STREAM = "<s:stream xmlns:s='http://etherx.jabber.org/streams' xmlns='jabber:client'>"
CAD = 'http://sids.mg.gov.br/cad'
BOUND = 'ie@jcad.sids.mg.gov.br/resource_part'
BROKER_TIME = 1700000000000
TEST_PASSWORD = "fake-ç&\'\"🚒"


def scenario(compressed=False, filters=True, refused=False, silent=False, tls_context=None, prereq_refused=False, minimum_revision=None, dynamic_classpath=None):
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    port = listener.getsockname()[1]
    listener.listen()
    failures = []

    def serve():
        conn = None
        try:
            conn = listener.accept()[0]
            conn.settimeout(8)
            pending = b''
            history_pages = []
            encoder = decoder = None

            def expect(token):
                nonlocal pending
                token = token.encode()
                while token not in pending:
                    data = conn.recv(4096)
                    assert data, 'Cliente desconectou aguardando ' + repr(token)
                    pending += decoder.decompress(data) if decoder else data
                result, pending = pending.split(token, 1)
                return result.decode() + token.decode()

            def expect_cad(tag):
                xml = expect('</message>')
                message = ET.fromstring(xml.strip())
                assert message.tag == '{jabber:client}message', xml
                assert message.attrib['from'] == BOUND, xml
                children = list(message)
                assert len(children) == 1 and children[0].tag == '{' + CAD + '}' + tag, xml
                cad = children[0]
                if tag != 'envIniciarSessao':
                    assert BROKER_TIME <= int(cad.attrib['dataHoraEnvio']) < BROKER_TIME + 60000, xml
                if tag == 'envAlterarFiltroChamada':
                    assert cad.find('{'+CAD+'}idFiltro').text == '12'
                if tag == 'envAlterarFiltroRecurso':
                    assert cad.find('{'+CAD+'}idFiltro').text == '34'
                if tag == 'envValidarPreRequisitos':
                    assert cad.find('{'+CAD+'}version').text == '4.4.55', xml
                    assert cad.find('{'+CAD+'}revision').text == '279', xml
                    assert cad.find('{'+CAD+'}brokerMinimumRevision').text == (minimum_revision or '279'), xml
                if tag == 'envRecuperarResumosHistoricosChamada':
                    assert cad.find('{'+CAD+'}idChamada').text == '123', xml
                    assert cad.attrib['pageSize'] == '50', xml
                    history_pages.append(int(cad.attrib['currentPage']))
                    assert len(cad.find('{'+CAD+'}orgaosAtendimentoEmergencia')) == 7, xml
                    assert len(cad.find('{'+CAD+'}tiposHistoricosChamada')) == 2, xml
                return cad.attrib['id']

            def send(xml, fragment=False):
                if CAD in xml:
                    xml = "<message xmlns='jabber:client'>" + xml + "</message>"
                data = xml.encode()
                if encoder:
                    data = encoder.compress(data) + encoder.flush(zlib.Z_SYNC_FLUSH)
                if fragment:
                    for byte in data:
                        conn.sendall(bytes([byte]))
                else:
                    conn.sendall(data)

            def response(tag, id_, content='', status=0):
                send(f"<c:{tag} xmlns:c='{CAD}' id='{id_}' status='{status}'>{content}</c:{tag}>")

            def open_stream():
                # Consume the entire root tag, independent of attribute ordering.
                expect('<stream:stream')
                header = expect('>')
                assert "from='ie@cad.sids.mg.gov.br'" in header, 'Remetente inicial diferente do cliente CAD original'

            open_stream()
            if tls_context:
                send(STREAM + "<s:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/></s:features>")
                expect("<starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>")
                send("<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>")
                conn = tls_context.wrap_socket(conn, server_side=True)
                open_stream()
            # Exercise compression before SASL when it is the only advertised feature.
            if compressed and tls_context:
                send(STREAM + "<s:features><compression xmlns='http://jabber.org/features/compress'><method>zlib</method></compression></s:features>")
                expect('</compress>')
                send("<compressed xmlns='http://jabber.org/protocol/compress'/>")
                encoder, decoder = zlib.compressobj(), zlib.decompressobj()
                open_stream()
            send(STREAM + "<s:features><mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><mechanism>PLAIN</mechanism></mechanisms>" +
                 ("<compression xmlns='http://jabber.org/features/compress'><method>zlib</method></compression>" if compressed and not encoder else '') + "</s:features>")
            auth = expect('</auth>')  # SASL takes priority when both features are offered.
            encoded = re.search(r'<auth[^>]*>([^<]+)</auth>', auth).group(1)
            assert base64.b64decode(encoded).decode('utf-8') == '\0TEST\0' + TEST_PASSWORD, 'Payload SASL diferente do informado' 
            send("<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>", True)
            open_stream()
            if compressed and not encoder:
                send(STREAM + "<s:features><compression xmlns='http://jabber.org/features/compress'><method>zlib</method></compression></s:features>")
                expect('</compress>')
                send("<compressed xmlns='http://jabber.org/protocol/compress'/>")
                encoder, decoder = zlib.compressobj(), zlib.decompressobj()
                open_stream()
            send(STREAM + "<s:features><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'/></s:features>")
            bind = expect('</iq>')
            bind_id = re.search("id='([^']+)'", bind).group(1)
            send(f"<iq type='result' id='{bind_id}'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><jid>{BOUND}</jid></bind></iq>")
            session_id = expect_cad('envIniciarSessao')
            response('rtnIniciarSessao', session_id, f'<brokerTimestamp xmlns="{CAD}">{BROKER_TIME}</brokerTimestamp>', 1 if refused else 0)
            if refused:
                return
            before_id = expect_cad('envRecuperarCacheAntesValidarPreRequisitos')
            response('rtnRecuperarCacheAntesValidarPreRequisitos', before_id)
            validation_id = expect_cad('envValidarPreRequisitos')
            response('rtnValidarPreRequisitos', validation_id,
                     f"<motivo xmlns='{CAD}'>VERSAO_INCOMPATIVEL</motivo>" if prereq_refused else '',
                     6 if prereq_refused else 0)
            if prereq_refused:
                return
            if not filters or dynamic_classpath:
                cache_id = expect_cad('envRecuperarCacheAposValidarPreRequisitos')
                if silent:
                    # Traffic continues, but no CAD response: watchdog must still expire.
                    for i in range(40):
                        try:
                            send(f"<iq type='get' id='p{i}'><ping xmlns='urn:xmpp:ping'/></iq>")
                            expect('/>')
                        except (OSError, AssertionError):
                            break
                        time.sleep(0.1)
                    else:
                        raise AssertionError('Timeout CAD não encerrou sessão com pings ativos')
                    return
                response('rtnRecuperarCacheAposValidarPreRequisitos', cache_id,
                         f"<filtrosChamada xmlns='{CAD}'><i id='12'><descricao>2BBM</descricao><composicoesFiltroChamada><i id='999'><idFiltro>12</idFiltro></i></composicoesFiltroChamada></i></filtrosChamada>"
                         f"<filtrosRecurso xmlns='{CAD}'><i id='34'><descricao>2BBM</descricao><idsUnidades><i>15186</i></idsUnidades></i></filtrosRecurso>")
            if filters:
                if dynamic_classpath:
                    summary_id = expect_cad('envRecuperarResumosChamadasAtivas')
                    response('rtnRecuperarResumosChamadasAtivas', summary_id,
                        f"<resumosChamadas xmlns='{CAD}'><i><idChamada>99999</idChamada><numeroChamada>OUTSIDE-SUMMARY</numeroChamada></i></resumosChamadas>")
                chamada_id = expect_cad('envAlterarFiltroChamada')
                recurso_id = expect_cad('envAlterarFiltroRecurso')
                # Multiple response stanzas in one packet, with correlation IDs.
                send(f"<rtnAlterarFiltroChamada xmlns='{CAD}' id='{chamada_id}' status='0'/><rtnAlterarFiltroRecurso xmlns='{CAD}' id='{recurso_id}' status='0'/>")
                if dynamic_classpath:
                    summary_refused_id = expect_cad('envRecuperarResumosChamadasAtivas')
                    response('rtnRecuperarResumosChamadasAtivas', summary_refused_id, status=7)
                chamada_id = expect_cad('envRecuperarResumosChamadasAtivas')
                recurso_id = expect_cad('envRecuperarResumosRecursosParaEmpenho')
                chamada = f"<resumoChamada xmlns='{CAD}'><idChamada>123</idChamada><numeroChamada>2026-123</numeroChamada><situacaoChamada>EM_ATENDIMENTO</situacaoChamada><descricaoLocalizacao>Ocorrência ação 🚒</descricaoLocalizacao></resumoChamada>"
                recurso = f"<resumoRecurso xmlns='{CAD}'><idRecurso>456</idRecurso><idUnidadeServico>15186</idUnidadeServico><idTipoRecurso>1</idTipoRecurso><prefixo>AB-01</prefixo><situacaoRecurso>DISPONIVEL</situacaoRecurso><tipoRecurso>VIATURA</tipoRecurso><placa>ABC1234</placa></resumoRecurso>"
                response('rtnRecuperarResumosChamadasAtivas', chamada_id, f"<resumosChamadas xmlns='{CAD}'>" + chamada.replace('resumoChamada', 'i') + '</resumosChamadas>')
                response('rtnRecuperarResumosRecursosParaEmpenho', recurso_id, f"<resumosRecursos xmlns='{CAD}'>" + recurso.replace('resumoRecurso', 'i') + '</resumosRecursos>')
                updated = recurso.replace('DISPONIVEL', 'EMPENHADO')
                if dynamic_classpath:
                    updated = updated.replace('</resumoRecurso>', '<resumosEmpenhosRecurso><i><idChamada>123</idChamada><numeroChamada>2026-123</numeroChamada><situacaoEmpenhoRecurso>DESPACHADO</situacaoEmpenhoRecurso></i></resumosEmpenhosRecurso></resumoRecurso>')
                send(f"<envInformarAlteracaoChamada xmlns='{CAD}'>{chamada}</envInformarAlteracaoChamada><envInformarAlteracaoRecurso xmlns='{CAD}'>{updated}</envInformarAlteracaoRecurso>", True)
                if dynamic_classpath:
                    for page in (1, 2):
                        history_id = expect_cad('envRecuperarResumosHistoricosChamada')
                        response('rtnRecuperarResumosHistoricosChamada', history_id,
                            f"<paginationBean xmlns='{CAD}'><currentPage>{page}</currentPage><totalSize>51</totalSize><pageSize>50</pageSize></paginationBean><resumosHistoricosChamada xmlns='{CAD}'><i><descricao>Registro {page}</descricao><dataHoraInclusao>170000000000{page}</dataHoraInclusao></i></resumosHistoricosChamada>")
                if dynamic_classpath:
                    # Repetição/classificação não deve consultar histórico; somente o sinal específico abaixo.
                    send(f"<envInformarAlteracaoChamada xmlns='{CAD}'>{chamada}<tiposAcoesGeramNotificacao><i>CLASSIFICAR_CHAMADA</i></tiposAcoesGeramNotificacao></envInformarAlteracaoChamada>")
                    send(f"<envInformarAlteracaoChamada xmlns='{CAD}'>{chamada}<tiposAcoesGeramNotificacao><i>SALVAR_HISTORICO_CHAMADA</i></tiposAcoesGeramNotificacao></envInformarAlteracaoChamada>")
                    history_id = expect_cad('envRecuperarResumosHistoricosChamada')
                    response('rtnRecuperarResumosHistoricosChamada', history_id, status=7)
                    assert history_pages == [1, 2, 1], history_pages
                    outside = recurso.replace('<idRecurso>456</idRecurso>', '<idRecurso>9999</idRecurso>').replace('<idUnidadeServico>15186</idUnidadeServico>', '<idUnidadeServico>888</idUnidadeServico>')
                    send(f"<envInformarAlteracaoRecurso xmlns='{CAD}'>{outside}</envInformarAlteracaoRecurso>")
            send("<iq type='get' id='server-ping'><ping xmlns='urn:xmpp:ping'/></iq>")
            pong = expect('/>')
            assert "id='server-ping'" in pong, pong
            send('</s:stream>')
        except Exception as exc:
            failures.append(exc)
        finally:
            if conn:
                conn.close()
            listener.close()

    server = threading.Thread(target=serve, daemon=True)
    server.start()
    args = ['java', '-cp', str(ROOT / 'target/java-cad-reader-1.0.0.jar') + (':' + dynamic_classpath if dynamic_classpath else ''),
            'br.gov.mg.sids.reader.ReaderSessionTest' if dynamic_classpath else 'br.gov.mg.sids.reader.CadXmppReader', '--host', '127.0.0.1', '--port', str(port),
            '--user', 'test', '--password', TEST_PASSWORD, '--request-timeout', '2' if silent else '10']
    if minimum_revision:
        args += ['--broker-minimum-revision', minimum_revision]
    if filters and not dynamic_classpath:
        args += ['--chamada-filter-id', '12', '--recurso-filter-id', '34']
    if tls_context:
        args += ['--insecure']
    result = subprocess.run(args, capture_output=True, text=True, timeout=20)
    server.join(10)
    assert not server.is_alive(), 'Servidor simulado não terminou'
    assert not failures, (failures, result.stdout, result.stderr)
    assert TEST_PASSWORD not in result.stdout + result.stderr
    if refused or prereq_refused:
        assert result.returncode == 1 and 'recusado' in result.stderr, result
        if prereq_refused:
            assert 'FALHA_VERIFICACAO_PRE_REQUISITOS' in result.stderr and 'VERSAO_INCOMPATIVEL' in result.stderr, result
            assert '[REQUEST] envAlterarFiltro' not in result.stdout, result
            assert '[REQUEST] envRecuperarCacheApos' not in result.stdout, result
    elif silent:
        assert result.returncode == 1 and 'Sem resposta CAD' in result.stderr, result
    else:
        assert result.returncode == 0 and not result.stderr, result.stderr
        if filters:
            assert 'Ocorrência ação 🚒' in result.stdout, result.stdout
            assert '[OCORRENCIA] ATUALIZACAO id=123' in result.stdout, result.stdout
            assert '[RECURSO] ATUALIZACAO id=456 prefixo=AB-01 situação=EMPENHADO' in result.stdout, result.stdout
            assert 'disponíveis neste lote=1' in result.stdout, result.stdout
            assert '[CAD-XML]' not in result.stdout, result.stdout
        else:
            assert '--chamada-filter-id 12 | 2BBM' in result.stdout, result.stdout
            assert '--recurso-filter-id 34 | 2BBM' in result.stdout, result.stdout
            assert '[FILTROS] filtroChamada: 1' in result.stdout and '[FILTROS] filtroRecurso: 1' in result.stdout, result.stdout
    print(f'PASS zlib={compressed} filtros={filters} recusa={refused} timeout={silent} TLS={bool(tls_context)} prereq_recusa={prereq_refused} filtros_na_sessao={bool(dynamic_classpath)}')


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as classes:
        subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', str(ROOT / 'target/java-cad-reader-1.0.0.jar'), '-d', classes, str(ROOT / 'tests/ReaderSessionTest.java')], check=True)
        scenario(dynamic_classpath=classes)
    scenario()
    scenario(compressed=True, minimum_revision="200")
    scenario(compressed=True, refused=True)
    scenario(filters=False)
    scenario(prereq_refused=True)
    scenario(filters=False, silent=True)
    with tempfile.TemporaryDirectory() as directory:
        cert, key = pathlib.Path(directory) / 'cert.pem', pathlib.Path(directory) / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-days', '1', '-subj', '/CN=localhost'],
                       check=True, capture_output=True)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        scenario(compressed=True, tls_context=context)
