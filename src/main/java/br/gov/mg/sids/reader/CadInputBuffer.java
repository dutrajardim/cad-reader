package br.gov.mg.sids.reader;

import java.io.*;
import java.util.Objects;

/** Buffer que retorna o bloco disponível sem tentar uma segunda leitura de rede. */
final class CadInputBuffer extends BufferedInputStream {
    CadInputBuffer(InputStream input) {
        super(input);
    }

    /**
     * InflaterInputStream.available() pode retornar 1 sem haver novos bytes na rede.
     * O BufferedInputStream padrão tentaria preencher novamente e bloquearia o parser
     * antes de ele despachar a mensagem já recebida. Fazemos no máximo um refill.
     */
    @Override
    public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IndexOutOfBoundsException();
        if (length == 0)
            return 0;
        if (count > pos)
            return super.read(bytes, offset, Math.min(length, count - pos));
        int first = super.read();
        if (first < 0)
            return -1;
        bytes[offset] = (byte) first;
        int extra = Math.min(length - 1, count - pos);
        return extra == 0 ? 1 : 1 + super.read(bytes, offset + 1, extra);
    }
}
