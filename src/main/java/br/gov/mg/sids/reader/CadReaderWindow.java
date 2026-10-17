package br.gov.mg.sids.reader;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.w3c.dom.Element;

/** Tela desktop: configura uma sessão CAD e, opcionalmente, publica em um fórum Discord. */
public final class CadReaderWindow extends JFrame {
    private final JTextField host = new JTextField("cad.sids.mg.gov.br"), port = new JTextField("7001"),
                             user = new JTextField();
    private final JComboBox<String> profile = new JComboBox<>();
    private final JCheckBox saveCadPassword = new JCheckBox("Salvar senha CAD criptografada (opcional)");
    private boolean profileBusy;
    private final JPasswordField password = new JPasswordField(), webhook = new JPasswordField(),
                                 protection = new JPasswordField();
    private final JCheckBox insecure = new JCheckBox("Desativar validação TLS (diagnóstico)", true),
                            publish = new JCheckBox("Publicar no fórum Discord ao iniciar monitoramento");
    private final FilterSelector calls = new FilterSelector(), resources = new FilterSelector();
    private final JTextArea logs = new JTextArea(), preview = new JTextArea();
    private final JLabel deliveryState = new JLabel("Discord: publicação desativada");
    private final DefaultTableModel rows
        = new DefaultTableModel(new String[] {"Recebido (UTC)", "Viatura", "Chamada", "Evento"}, 0) {
              public boolean isCellEditable(int r, int c) {
                  return false;
              }
          };
    private final List<CadMonitor.Event> events = new ArrayList<>();
    private final ArrayBlockingQueue<CadMonitor.Event> pendingEvents = new ArrayBlockingQueue<>(5000);
    private final ArrayBlockingQueue<String> pendingLogs = new ArrayBlockingQueue<>(2000);
    private final javax.swing.Timer displayTimer = new javax.swing.Timer(150, e -> flushDisplay());
    private JTable table;
    private final ExecutorService tasks = Executors.newCachedThreadPool();
    private final Path root = java.nio.file.Paths.get(System.getProperty("user.home"), ".java-cad-reader");
    private final JPasswordField botToken = new JPasswordField();
    private final JTextField botGuild = new JTextField(), botForum = new JTextField(), botRole = new JTextField();
    private volatile DiscordBot bot;
    private volatile CadSummary summary;
    private boolean botStarting, botStopping;
    private String botNotice = "parado";
    private final JLabel botState = new JLabel("  Bot: parado");
    private final JButton configureBotButton = new JButton("Configurar bot"),
                          startBotButton = new JButton("Iniciar bot"),
                          stopBotButton = new JButton("Parar bot");
    private volatile CadXmppReader reader;
    private volatile CadMonitor activeMonitor;
    private volatile Map<String, CadResourceFilter> resourceFilters = Collections.emptyMap();
    private volatile DiscordPublisher discord;
    private volatile boolean monitoring;
    private volatile long generation;
    private String savedCall = "", savedResource = "";

    /** Todos os componentes Swing são criados e atualizados no EDT. */
    public CadReaderWindow() {
        super("CAD Reader • Ocorrências e viaturas");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);
        JPanel config = new JPanel(new GridLayout(0, 2, 8, 5));
        config.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        profile.setEditable(true);
        profile.getEditor().setItem("minha-unidade");
        refreshProfiles("minha-unidade");
        field(config, "Perfil local (selecione ou digite um nome)", profile);
        field(config, "Senha de proteção do perfil", protection);
        JPanel profileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        button(profileButtons, "Abrir perfil", this::loadProfile);
        button(profileButtons, "Salvar perfil", this::saveProfile);
        button(profileButtons, "Remover perfil", this::removeProfile);
        button(profileButtons, "Exportar perfil", () -> transferProfile(false));
        button(profileButtons, "Importar perfil", () -> transferProfile(true));
        field(config, "Gerenciar perfil neste computador", profileButtons);
        field(config, "Servidor / domínio CAD", host);
        field(config, "Porta TCP", port);
        field(config, "Usuário CAD", user);
        JPanel passwordPanel = new JPanel(new BorderLayout(6, 0));
        JCheckBox showPassword = new JCheckBox("Mostrar");
        char passwordEcho = password.getEchoChar();
        showPassword.addActionListener(
            e -> password.setEchoChar(showPassword.isSelected() ? (char)0 : passwordEcho));
        passwordPanel.add(password, BorderLayout.CENTER);
        passwordPanel.add(showPassword, BorderLayout.EAST);
        password.setToolTipText("Digite somente a senha; as aspas usadas no terminal não fazem parte dela.");
        field(config, "Senha CAD", passwordPanel);
        field(config, "Credenciais no perfil", saveCadPassword);
        field(config, "Certificado do servidor", insecure);
        JPanel connectionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        button(connectionButtons, "1. Conectar e buscar filtros", this::connect);
        button(connectionButtons, "Desconectar", this::disconnect);
        field(config, "Conexão CAD", connectionButtons);
        field(config, "Filtro de chamadas", calls);
        field(config, "Filtro de recursos", resources);
        field(config, "URL completa do webhook do fórum Discord", webhook);

        field(config, "Notificações", publish);
        JPanel botButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        configureBotButton.addActionListener(e -> configureBot());
        startBotButton.addActionListener(e -> startBot());
        stopBotButton.addActionListener(e -> stopBot());
        botButtons.add(configureBotButton);
        botButtons.add(startBotButton);
        botButtons.add(stopBotButton);
        button(botButtons, "Ajuda", this::botHelp);
        field(config, "Comandos Discord (opcional)", botButtons);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(buttons, "2. Iniciar monitoramento", this::monitor);
        button(buttons, "Reenviar pendentes", () -> {
            if (discord != null)
                discord.retry();
            else
                info("Inicie com publicação habilitada para acessar a fila.");
        });
        button(buttons, "Limpar pendentes", () -> {
            DiscordPublisher publisher = discord;
            if (publisher == null) {
                info("Inicie com publicação habilitada para acessar a fila.");
                return;
            }
            if (JOptionPane.showConfirmDialog(this,
                    "Descartar todas as publicações pendentes desta sessão/webhook?\n"
                    + "O histórico local e as mensagens já publicadas serão mantidos.\n"
                    + "Um envio em andamento pode ser concluído. Novas atualizações continuarão sendo publicadas.",
                    "Limpar pendentes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION)
                publisher.clearPending();
        });
        button(buttons, "Exportar CSV", this::export);
        JPanel top = new JPanel(new BorderLayout());
        top.add(config, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        table = new JTable(rows);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0 && table.convertRowIndexToModel(row) < events.size()) {
                CadMonitor.Event event = events.get(table.convertRowIndexToModel(row));
                preview.setText(event.title + "\n" + event.body + event.links
                                + "\nRecebido: " + event.observed);
            }
        });
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        logs.setEditable(false);
        JTabbedPane tabs = new JTabbedPane();
        JSplitPane split
            = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(preview));
        split.setResizeWeight(.65);
        tabs.add("Eventos e prévia da mensagem", split);
        tabs.add("Conexão e publicação", new JScrollPane(logs));
        add(tabs, BorderLayout.CENTER);
        JLabel hint = new JLabel("  Fluxo: conectar → escolher os dois filtros → iniciar. Um publicador por "
                                 + "unidade. Fotos e conversas ficam no Discord.");
        JPanel footer = new JPanel(new GridLayout(0, 1));
        footer.add(deliveryState);
        footer.add(botState);
        footer.add(hint);
        add(footer, BorderLayout.SOUTH);
        refreshBotControls();
        displayTimer.start();
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                displayTimer.stop();
                disconnect();
                tasks.shutdown();
                dispose();
            }
        });
    }
    private void field(JPanel p, String label, JComponent value) {
        p.add(new JLabel(label));
        p.add(value);
    }
    private void button(JPanel p, String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> {
            try {
                action.run();
            } catch (Exception ex) {
                info(ex.getMessage());
            }
        });
        p.add(b);
    }
    private void info(String s) {
        JOptionPane.showMessageDialog(this, s, "CAD Reader", JOptionPane.INFORMATION_MESSAGE);
    }
    /** Filas limitadas evitam sobrecarregar o EDT durante a carga inicial. */
    private void log(String s) {
        if (!pendingLogs.offer(s)) {
            pendingLogs.poll();
            pendingLogs.offer(s);
        }
    }

    private void flushDisplay() {
        refreshBotControls();
        DiscordPublisher publisher = discord;
        String status
            = publisher == null ? "Discord: publicação desativada nesta sessão" : publisher.status();
        if (!("  " + status).equals(deliveryState.getText())) {
            deliveryState.setText("  " + status);
            deliveryState.setToolTipText(status);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String line = pendingLogs.poll();
            if (line == null)
                break;
            text.append(line).append('\n');
        }
        if (text.length() > 0) {
            logs.append(text.toString());
            if (logs.getDocument().getLength() > 100000)
                logs.replaceRange("", 0, 30000);
            logs.setCaretPosition(logs.getDocument().getLength());
        }
        List<CadMonitor.Event> batch = new ArrayList<>();
        pendingEvents.drainTo(batch, 250);
        if (batch.isEmpty())
            return;
        int selectedRow = table.getSelectedRow();
        CadMonitor.Event selectedEvent
            = selectedRow < 0 ? null : events.get(table.convertRowIndexToModel(selectedRow));
        table.clearSelection();
        for (CadMonitor.Event event : batch) {
            events.add(event);
            rows.getDataVector().add(
                new Vector<>(Arrays.asList(event.observed, event.resource, event.number, event.title)));
        }
        int excess = events.size() - 5000;
        if (excess > 0) {
            events.subList(0, excess).clear();
            rows.getDataVector().subList(0, excess).clear();
        }
        rows.fireTableDataChanged();
        CadMonitor.Event last = batch.get(batch.size() - 1);
        int restored = selectedEvent == null ? -1 : events.indexOf(selectedEvent);
        if (restored >= 0) {
            int viewRow = table.convertRowIndexToView(restored);
            table.setRowSelectionInterval(viewRow, viewRow);
        } else
            preview.setText(last.title + "\n" + last.body + last.links);
    }
    private Path profilePath() {
        String name = profile.getEditor().getItem().toString().trim();
        if (!name.matches("[A-Za-z0-9_-]{1,60}"))
            throw new IllegalArgumentException("Perfil: use letras sem acento, números, _ ou - (até 60).");
        return root.resolve("perfis").resolve(name + ".properties");
    }
    private Properties settings() {
        Properties p = new Properties();
        p.setProperty("host", host.getText().trim());
        p.setProperty("port", port.getText().trim());
        p.setProperty("user", user.getText().trim());
        p.setProperty("calls", selected(calls).isEmpty() ? savedCall : selected(calls));
        p.setProperty("resources", selected(resources).isEmpty() ? savedResource : selected(resources));
        p.setProperty("botGuild", botGuild.getText().trim());
        p.setProperty("botForum", botForum.getText().trim());
        p.setProperty("botRole", botRole.getText().trim());
        p.setProperty("botToken", new String(botToken.getPassword()).trim());
        return p;
    }
    /** Lista apenas perfis locais; uma seleção não abre nem decifra o arquivo automaticamente. */
    private void refreshProfiles(String selected) {
        java.util.List<String> names = new ArrayList<>();
        Path directory = root.resolve("perfis");
        if (Files.isDirectory(directory)) {
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                files.map(f -> f.getFileName().toString())
                    .filter(n -> n.matches("[A-Za-z0-9_-]{1,60}\\.properties"))
                    .map(n -> n.substring(0, n.length() - 11))
                    .sorted()
                    .forEach(names::add);
            } catch (Exception e) {
                log("Não foi possível listar perfis locais.");
            }
        }
        profile.setModel(new DefaultComboBoxModel<>(names.toArray(new String[0])));
        profile.setSelectedItem(selected);
    }

    /** Criptografia e acesso ao disco ocorrem fora da thread da interface. */
    private void saveProfile() {
        if (profileBusy) {
            info("Aguarde a operação do perfil terminar.");
            return;
        }
        Path path = profilePath();
        String name = profile.getEditor().getItem().toString().trim();
        Properties settings = settings();
        char[] pass = protection.getPassword();
        String url = new String(webhook.getPassword()).trim();
        String cad = saveCadPassword.isSelected() ? new String(password.getPassword()) : "";
        // A autenticação limpa o campo; não substitua uma senha salva silenciosamente por vazio.
        if (saveCadPassword.isSelected() && cad.isEmpty()) {
            Arrays.fill(pass, '\0');
            info("Digite a senha CAD para salvá-la, ou desmarque Salvar senha CAD criptografada.");
            return;
        }
        profileBusy = true;
        log("Salvando perfil…");
        tasks.submit(() -> {
            String error = null;
            try {
                new ProfileStore(path).save(settings, url, cad, pass);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                Arrays.fill(pass, '\0');
            }
            final String failure = error;
            SwingUtilities.invokeLater(() -> {
                profileBusy = false;
                if (!isDisplayable())
                    return;
                if (failure != null) {
                    info("Não foi possível salvar o perfil: " + failure);
                    return;
                }
                refreshProfiles(name);
                log("Perfil salvo. Webhook: " + (url.isEmpty() ? "não salvo" : "criptografado")
                    + "; senha CAD: " + (cad.isEmpty() ? "não salva" : "criptografada")
                    + "; token do bot: " + (settings.getProperty("botToken", "").isEmpty() ? "não salvo" : "criptografado")
                    + ". A senha de proteção não é gravada.");
            });
        });
    }

    /** Decifra fora do EDT e aplica as preferências somente após sucesso completo. */
    private void loadProfile() {
        if (reader != null) {
            info("Desconecte antes de abrir outro perfil.");
            return;
        }
        if (profileBusy) {
            info("Aguarde a operação do perfil terminar.");
            return;
        }
        Path path = profilePath();
        char[] pass = protection.getPassword();
        profileBusy = true;
        log("Abrindo perfil…");
        tasks.submit(() -> {
            Properties loaded = null;
            String error = null;
            try {
                loaded = new ProfileStore(path).load(pass);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                Arrays.fill(pass, '\0');
            }
            final Properties p = loaded;
            final String failure = error;
            SwingUtilities.invokeLater(() -> {
                profileBusy = false;
                if (!isDisplayable())
                    return;
                if (failure != null) {
                    info("Não foi possível abrir o perfil: " + failure);
                    return;
                }
                host.setText(p.getProperty("host", "cad.sids.mg.gov.br"));
                port.setText(p.getProperty("port", "7001"));
                user.setText(p.getProperty("user", ""));
                webhook.setText(p.getProperty("webhook", ""));
                botToken.setText(p.getProperty("botToken", ""));
                botGuild.setText(p.getProperty("botGuild", ""));
                botForum.setText(p.getProperty("botForum", ""));
                botRole.setText(p.getProperty("botRole", ""));
                p.remove("botToken");
                savedCall = p.getProperty("calls", "");
                savedResource = p.getProperty("resources", "");
                password.setText(p.getProperty("password", ""));
                saveCadPassword.setSelected(!p.getProperty("password", "").isEmpty());
                log("Perfil aberto. Webhook: "
                    + (p.getProperty("webhook", "").isEmpty() ? "não salvo" : "recuperado (oculto)")
                    + "; senha CAD: "
                    + (password.getPassword().length == 0 ? "digite para conectar" : "recuperada (oculta)")
                    + "; token do bot: " + (botToken.getPassword().length == 0 ? "não salvo" : "recuperado (oculto)")
                    + ".");
                p.remove("password");
                p.remove("webhook");
                log("Conecte para atualizar o catálogo de filtros.");
            });
        });
    }

    /** Transporta somente o perfil salvo, validando os segredos fora da thread da interface. */
    private void transferProfile(boolean importing) {
        if (profileBusy) {
            info("Aguarde a operação do perfil terminar.");
            return;
        }
        if (importing && reader != null) {
            info("Desconecte antes de importar outro perfil.");
            return;
        }
        Path local = profilePath();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(importing ? "Importar perfil — informe antes a senha de proteção do arquivo"
                                         : "Exportar perfil salvo");
        chooser.setSelectedFile(local.getFileName().toFile());
        int result = importing ? chooser.showOpenDialog(this) : chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION)
            return;
        Path external = chooser.getSelectedFile().toPath().toAbsolutePath();
        Path source = importing ? external : local;
        Path destination = importing ? local : external;
        if (source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
            info("Escolha um arquivo diferente do perfil local.");
            return;
        }
        if (Files.exists(destination)
            && JOptionPane.showConfirmDialog(this, "Substituir " + destination.getFileName() + "?",
                                             "Substituir perfil", JOptionPane.YES_NO_OPTION)
                   != JOptionPane.YES_OPTION)
            return;
        String name = profile.getEditor().getItem().toString().trim();
        char[] pass = protection.getPassword();
        profileBusy = true;
        tasks.submit(() -> {
            String error = null;
            try {
                new ProfileStore(source).copyTo(destination, pass);
            } catch (Exception e) {
                error = e instanceof IllegalArgumentException ? e.getMessage()
                                                              : "Não foi possível ler ou gravar o arquivo de perfil.";
            } finally {
                Arrays.fill(pass, '\0');
            }
            String failure = error;
            SwingUtilities.invokeLater(() -> {
                profileBusy = false;
                if (!isDisplayable())
                    return;
                if (failure != null) {
                    info(failure);
                    return;
                }
                if (importing)
                    refreshProfiles(name);
                log(importing ? "Perfil importado. Use Abrir perfil com a mesma senha de proteção."
                              : "Perfil salvo exportado. No outro computador, importe com a mesma senha de proteção.");
            });
        });
    }

    /** Remove apenas o perfil escolhido; histórico e fila de publicação são preservados. */
    private void removeProfile() {
        if (profileBusy) {
            info("Aguarde a operação do perfil terminar.");
            return;
        }
        Path path = profilePath();
        if (JOptionPane.showConfirmDialog(this,
                                          "Remover o perfil " + path.getFileName() + " deste computador?",
                                          "Remover perfil", JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION)
            return;
        try {
            if (!Files.deleteIfExists(path)) {
                info("Perfil não encontrado.");
                return;
            }
            refreshProfiles("minha-unidade");
            protection.setText("");
            log("Perfil removido. Histórico e mensagens pendentes foram preservados.");
        } catch (Exception e) {
            info("Não foi possível remover o perfil.");
        }
    }
    private String selected(FilterSelector box) {
        return box.selectedId();
    }

    /** A conexão bloqueante roda em executor; callbacks encaminham a apresentação ao EDT. */
    private void connect() {
        if (profileBusy) {
            info("Aguarde a operação do perfil terminar.");
            return;
        }
        if (reader != null) {
            info("Já existe uma conexão. Desconecte para iniciar outra.");
            return;
        }
        Map<String, String> options = new HashMap<>();
        options.put("host", host.getText().trim());
        options.put("to", host.getText().trim());
        options.put("port", port.getText().trim());
        options.put("user", user.getText().trim());
        options.put("password", new String(password.getPassword()));
        if (insecure.isSelected())
            options.put("insecure", "true");
        ReaderOptions parsed = ReaderOptions.from(options);
        user.setText(parsed.username);
        calls.clear();
        resources.clear();
        long connectionGeneration = ++generation;
        CadMonitor monitor = new CadMonitor(e -> {
            if (connectionGeneration == generation)
                event(e);
        });
        monitor.setDiagnostics(message -> {
            if (connectionGeneration == generation)
                log("[PUBLICACAO] " + message);
        });
        activeMonitor = monitor;
        resourceFilters = Collections.emptyMap();
        CadXmppReader session = new CadXmppReader(parsed, new CadListener() {
            public void onLog(String category, String text) {
                if ("AUTH".equals(category) && "Autenticação confirmada".equals(text))
                    SwingUtilities.invokeLater(() -> {
                        if (connectionGeneration == generation)
                            password.setText("");
                    });
                log("[" + category + "] " + text);
            }
            public void onFilters(Element cache) {
                if (connectionGeneration != generation)
                    return;
                Map<String, CadResourceFilter> parsedFilters = new HashMap<>();
                for (Element f : CadXml.collectionItems(cache, "filtrosRecurso", "filtroRecurso")) {
                    CadResourceFilter filter = CadResourceFilter.from(f);
                    parsedFilters.put(filter.id, filter);
                }
                resourceFilters = Collections.unmodifiableMap(parsedFilters);
                CadSummary report = summary;
                if (report != null) report.catalog(cache);
                List<FilterSelector.Option> c = filters(cache, "filtrosChamada", "filtroChamada"),
                                            r = filters(cache, "filtrosRecurso", "filtroRecurso");
                SwingUtilities.invokeLater(() -> {
                    if (reader != null && connectionGeneration == generation) {
                        fill(calls, c, savedCall);
                        fill(resources, r, savedResource);
                        log("Selecione os filtros e clique em Iniciar monitoramento.");
                    }
                });
            }
            public void onHistoryFinished(String callId, boolean complete) {
                monitor.historyFinished(callId, complete);
            }
            public void onMessage(Element message) {
                CadSummary report = summary;
                if (report != null && connectionGeneration == generation) report.accept(message);
                monitor.accept(message);
            }
        });
        summary = new CadSummary(session);
        monitor.setHistoryRequest(id -> {
            if (connectionGeneration != generation)
                return;
            try {
                session.requestHistory(id);
            } catch (Exception e) {
                monitor.historyFinished(id, false);
                log("[HISTORICO] Não foi possível consultar a chamada " + id + ".");
            }
        });
        monitor.setParticipantsRequest(id -> {
            if (connectionGeneration != generation)
                return;
            try {
                session.requestParticipants(id);
            } catch (Exception e) {
                log("[ENVOLVIDOS] Não foi possível consultar a chamada " + id + ".");
            }
        });
        reader = session;
        tasks.submit(() -> {
            try {
                session.start();
            } catch (Exception e) {
                log("Conexão encerrada: " + e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("CREDENCIAIS_INVALIDAS"))
                    log("[AUTH] O servidor recusou usuário/senha. A senha permanece no campo para "
                        + "conferência com Mostrar. Digite sem as aspas do comando de terminal; não "
                        + "compartilhe a senha nem capturas com ela visível.");
            } finally {
                session.close();
                SwingUtilities.invokeLater(() -> {
                    if (reader == session) {
                        reader = null;
                        stopBot();
                        summary = null;
                        stopPublisher();
                        monitoring = false;
                        publish.setEnabled(true);
                        log("Desconectado. Para retomar, conecte e selecione os filtros novamente.");
                    }
                });
            }
        });
    }
    private List<FilterSelector.Option> filters(Element e, String collection, String tag) {
        List<FilterSelector.Option> result = new ArrayList<>();
        for (Element f : CadXml.collectionItems(e, collection, tag))
            result.add(new FilterSelector.Option(f.getAttribute("id"), CadXml.text(f, "descricao")));
        return result;
    }
    private void fill(FilterSelector box, List<FilterSelector.Option> list, String saved) {
        box.setOptions(list, saved);
    }
    /** Abre o publicador somente depois da escolha explícita do usuário. */
    private void monitor() {
        if (reader == null) {
            info("Conecte primeiro.");
            return;
        }
        if (monitoring) {
            info("Desconecte antes de trocar filtros ou configuração de publicação.");
            return;
        }
        String c = selected(calls), r = selected(resources);
        if (c.isEmpty() || r.isEmpty()) {
            info("Selecione os dois filtros retornados pelo CAD.");
            return;
        }
        CadResourceFilter scope = resourceFilters.get(r);
        if (scope == null || scope.units.isEmpty()) {
            info("O filtro de recursos não contém unidades válidas. Reconecte para atualizar o catálogo.");
            return;
        }
        CadXmppReader session = reader;
        CadMonitor monitor = activeMonitor;
        long currentGeneration = generation;
        boolean publishing = publish.isSelected();
        String url = new String(webhook.getPassword()).trim();
        String lookupToken = new String(botToken.getPassword()).trim();
        if (publishing && lookupToken.isEmpty()) {
            info("Configure o token do bot para procurar tópicos existentes antes de publicar.");
            return;
        }
        monitoring = true;
        publish.setEnabled(false);
        log("Preparando monitoramento; a carga inicial será somente local…");
        tasks.submit(() -> {
            DiscordPublisher created = null;
            try {
                monitor.setResourceFilter(scope);
                if (publishing) {
                    created = new DiscordPublisher(url, root, this::log, null, scope);
                    created.setTopicLookup(new DiscordTopicLookup(lookupToken, url));
                }
                DiscordPublisher publisher = created;
                SwingUtilities.invokeLater(() -> {
                    if (reader != session || generation != currentGeneration) {
                        if (publisher != null)
                            CompletableFuture.runAsync(publisher::close);
                        return;
                    }
                    discord = publisher;
                    tasks.submit(() -> {
                        try {
                            session.applyFilters(c, r);
                            log("[FILTRO] Recursos restritos ao filtro " + r
                                + "; unidades=" + scope.units.size() + "; tipos=" + scope.types.size());
                            log("Monitoramento iniciado"
                                + (publisher == null ? " somente local."
                                                     : " com publicação no Discord. Somente mudanças após a "
                                                           + "carga inicial serão publicadas."));
                        } catch (Exception e) {
                            monitoringFailed(session, currentGeneration, e);
                        }
                    });
                });
            } catch (Exception e) {
                if (created != null)
                    created.close();
                monitoringFailed(session, currentGeneration, e);
            }
        });
    }

    private void monitoringFailed(CadXmppReader session, long expectedGeneration, Exception error) {
        SwingUtilities.invokeLater(() -> {
            if (reader != session || generation != expectedGeneration)
                return;
            monitoring = false;
            publish.setEnabled(true);
            stopPublisher();
            log("Não foi possível iniciar monitoramento: " + error.getMessage());
        });
    }
    /** Registra a cronologia local e encaminha a mesma representação ao Discord. */
    private void event(CadMonitor.Event event) {
        try {
            Path journal = root.resolve("historico.csv");
            synchronized (this) {
                Files.createDirectories(root);
                if (!Files.exists(journal))
                    AppFiles.write(journal, "recebido_utc;viatura;chamada;evento;detalhes\n");
                Java8.writeString(journal,
                                  csv(event.observed) + ";" + csv(event.resource) + ";" + csv(event.number)
                                      + ";" + csv(event.title) + ";" + csv(event.body) + "\n",
                                  StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log("Falha ao gravar histórico local.");
        }
        DiscordPublisher p = discord;
        if (event.publishable && !event.resourceId.isEmpty()) {
            if (p != null)
                p.publish(event);
            else
                log("[PUBLICACAO] viatura=" + event.resource + "; chamada=" + event.number
                    + "; evento somente local: publicação Discord desativada nesta sessão.");
        }
        if (!pendingEvents.offer(event)) {
            pendingEvents.poll();
            pendingEvents.offer(event);
        }
    }
    static String csv(String s) {
        if (s.matches("^[=+@-].*"))
            s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("historico-cad.csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path dest = chooser.getSelectedFile().toPath();
                if (Files.exists(dest)
                    && JOptionPane.showConfirmDialog(this, "Substituir arquivo existente?", "Exportar",
                                                     JOptionPane.YES_NO_OPTION)
                           != JOptionPane.YES_OPTION)
                    return;
                Files.copy(root.resolve("historico.csv"), dest, StandardCopyOption.REPLACE_EXISTING);
                log("Histórico exportado.");
            } catch (Exception e) {
                info("Histórico ainda não existe ou destino não pôde ser gravado.");
            }
        }
    }
    private void stopPublisher() {
        DiscordPublisher p = discord;
        discord = null;
        if (p != null)
            tasks.submit(p::close);
    }
    /** Aplica a configuração mesmo sem CAD; cancelar não altera os campos do perfil. */
    private void configureBot() {
        if (bot != null || botStarting || botStopping) {
            info("Pare o bot antes de alterar sua configuração.");
            return;
        }
        JPasswordField token = new JPasswordField(new String(botToken.getPassword()), 28);
        JTextField guild = new JTextField(botGuild.getText(), 28);
        JTextField forum = new JTextField(botForum.getText(), 28);
        JTextField role = new JTextField(botRole.getText(), 28);
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 6));
        field(panel, "Token do bot (não é webhook)", token);
        field(panel, "ID do servidor Discord", guild);
        field(panel, "ID do fórum permitido para limpeza", forum);
        field(panel, "ID do cargo autorizado (opcional)", role);
        panel.add(new JLabel("Sem cargo: administradores/Gerenciar servidor."));
        panel.add(new JLabel("Limpeza exige também Gerenciar mensagens."));
        panel.add(new JLabel("Aplicar não conecta ao Discord."));
        panel.add(new JLabel("Depois, use Salvar perfil para guardar os dados."));
        if (JOptionPane.showConfirmDialog(this, panel, "Configurar bot Discord",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            token.setText("");
            return;
        }
        botToken.setText(new String(token.getPassword()).trim());
        botGuild.setText(guild.getText().trim());
        botForum.setText(forum.getText().trim());
        botRole.setText(role.getText().trim());
        token.setText("");
        log("Bot: configuração aplicada. Use Salvar perfil para guardar o token criptografado e os IDs.");
        refreshBotControls();
    }

    /** Guia disponível antes da conexão para distinguir bot, webhook e permissões. */
    private void botHelp() {
        info("1. Crie uma aplicação no Discord Developer Portal e obtenha o token em Bot.\n"
            + "2. Instale no servidor com os escopos bot e applications.commands.\n"
            + "3. Permita ver canais, ler histórico, enviar mensagens, enviar em tópicos e gerenciar mensagens.\n"
            + "4. Em Integrações do servidor, autorize o cargo a usar /cad-reader.\n"
            + "5. Configure os IDs e token aqui; Salvar perfil guarda o token criptografado.\n"
            + "6. Conecte ao CAD, aguarde os filtros e clique em Iniciar bot.\n\n"
            + "/cad-reader totais-por-natureza filtro: — resposta privada com Publicar aqui.\n"
            + "/cad-reader limpar antes-de:AAAA-MM-DD — prévia e confirmação no tópico.\n"
            + "A limpeza inclui todos os autores e mídias anteriores à data e é irreversível.\n"
            + "O corte por data não identifica chamados ainda abertos.\n\n"
            + "O token do bot é diferente da URL do webhook. A aplicação precisa permanecer aberta.");
    }

    /** Controles e estado real do Gateway, atualizados exclusivamente no EDT. */
    private void refreshBotControls() {
        CadSummary report = summary;
        boolean ready = reader != null && report != null && report.hasFilters();
        configureBotButton.setEnabled(bot == null && !botStarting && !botStopping && !profileBusy);
        startBotButton.setEnabled(ready && bot == null && !botStarting && !botStopping && !profileBusy);
        startBotButton.setToolTipText(ready ? "Conectar o bot usando a configuração do perfil"
                                          : "Conecte ao CAD e aguarde o catálogo de filtros");
        stopBotButton.setEnabled(bot != null && !botStopping);
        String status = botStopping ? "encerrando…" : bot != null ? bot.status() : botNotice;
        botState.setText("  Bot: " + status);
        botState.setToolTipText(status);
    }

    /** Conexão explícita, separada da edição/salvamento da configuração. */
    private void startBot() {
        if (bot != null || botStarting || botStopping) return;
        CadSummary report = summary;
        if (reader == null || report == null || !report.hasFilters()) {
            info("Conecte ao CAD e aguarde o catálogo de filtros antes de iniciar o bot.");
            return;
        }
        String token = new String(botToken.getPassword()).trim();
        if (token.isEmpty()) { info("Use Configurar bot para informar o token e os IDs."); return; }
        final DiscordBot candidate;
        try {
            candidate = new DiscordBot(token, botGuild.getText().trim(), botForum.getText().trim(),
                                       botRole.getText().trim(), report, this::log);
        } catch (Exception e) { info(e.getMessage()); return; }
        bot = candidate;
        botStarting = true;
        refreshBotControls();
        log("Bot: iniciando e registrando /cad-reader no servidor configurado…");
        tasks.submit(() -> runBotStart(candidate, token));
    }

    /** Uma classe incompatível de um JAR atualizado também precisa encerrar o estado de início. */
    private void runBotStart(DiscordBot candidate, String token) {
        String error = null;
        try {
            log("Bot: tarefa de início em execução…");
            candidate.start(token, root);
        } catch (LinkageError incompatibleClasses) {
            error = "classes da aplicação incompatíveis (" + incompatibleClasses.getClass().getSimpleName()
                + "). Feche completamente o CAD-Reader e abra novamente o JAR atualizado.";
        } catch (Exception e) {
            error = e instanceof IllegalArgumentException || e instanceof IllegalStateException
                        || e instanceof DiscordBotApi.ApiFailure ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            final String failure = error;
            if (failure != null) {
                try { candidate.close(); }
                catch (Exception | LinkageError closeFailure) {
                    log("Bot: falha ao liberar recursos (" + closeFailure.getClass().getSimpleName() + "). Feche a aplicação.");
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (bot != candidate || botStopping) return;
                botStarting = false;
                if (failure != null) {
                    bot = null;
                    botNotice = "falha ao iniciar: " + failure;
                    log("Bot: não foi possível iniciar: " + failure);
                }
                refreshBotControls();
            });
        }
    }

    private void stopBot() {
        DiscordBot running = bot;
        if (running == null || botStopping) return;
        botStopping = true;
        refreshBotControls();
        log("Bot: encerrando. Uma operação já aceita pelo Discord pode ser concluída.");
        tasks.submit(() -> {
            running.close();
            SwingUtilities.invokeLater(() -> {
                if (bot != running) return;
                bot = null;
                botStarting = botStopping = false;
                botNotice = "parado";
                refreshBotControls();
            });
        });
    }
    private void disconnect() {
        stopBot();
        summary = null;
        password.setText("");
        generation++;
        CadXmppReader session = reader;
        reader = null;
        monitoring = false;
        publish.setEnabled(true);
        if (session != null)
            tasks.submit(session::close);
        stopPublisher();
        log("Desconectando; publicações não confirmadas permanecem em disco.");
    }
}
