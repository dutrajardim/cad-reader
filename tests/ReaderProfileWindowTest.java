package br.gov.mg.sids.reader;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Abre, salva e remove um perfil fictício usando os controles reais da interface. */
public final class ReaderProfileWindowTest {
    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
    static void waitProfile(CadReaderWindow window) throws Exception {
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        AtomicBoolean busy = new AtomicBoolean(true);
        do {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    busy.set((boolean)field(window, "profileBusy"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (!busy.get())
                return;
            Thread.sleep(20);
        } while (System.nanoTime() < limit);
        throw new AssertionError("Operação de perfil não terminou");
    }
    static JOptionPane pane(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JOptionPane)
                return (JOptionPane)c;
            if (c instanceof Container) {
                JOptionPane found = pane((Container)c);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("cad-profile-window-");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CadReaderWindow[] window = new CadReaderWindow[1];
        Path data = home.resolve(".java-cad-reader");
        Path file = data.resolve("perfis/teste.properties");
        char[] key = "protecao-ficticia".toCharArray();
        try {
            Properties settings = new Properties();
            settings.setProperty("user", "TESTE");
            settings.setProperty("calls", "123");
            settings.setProperty("resources", "456");
            settings.setProperty("botToken", "token-ficticio-para-perfil");
            settings.setProperty("botGuild", "123456");
            settings.setProperty("botForum", "234567");
            settings.setProperty("botRole", "345678");
            new ProfileStore(file).save(settings, "https://discord.com/api/webhooks/123/ficticio",
                                        "cad-ficticia", key);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    CadReaderWindow w = window[0] = new CadReaderWindow();
                    w.setVisible(true);
                    JComboBox<String> profiles = (JComboBox<String>)field(w, "profile");
                    if (!profiles.isEditable() || profiles.getItemCount() != 1)
                        throw new AssertionError("Lista de perfis");
                    profiles.setSelectedItem("teste");
                    ((JPasswordField)field(w, "protection")).setText(new String(key));
                    invoke(w, "loadProfile");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            waitProfile(window[0]);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    CadReaderWindow w = window[0];
                    if (!new String(((JPasswordField)field(w, "password")).getPassword())
                             .equals("cad-ficticia"))
                        throw new AssertionError("Senha CAD recuperada");
                    if (!new String(((JPasswordField)field(w, "botToken")).getPassword()).equals("token-ficticio-para-perfil")
                        || !((JTextField)field(w, "botForum")).getText().equals("234567") || field(w, "bot") != null)
                        throw new AssertionError("Perfil recupera bot sem iniciar conexão");
                    if (field(w, "reader") != null)
                        throw new AssertionError("Abrir perfil não deve conectar");
                    ((JCheckBox)field(w, "saveCadPassword")).setSelected(false);
                    invoke(w, "saveProfile");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            waitProfile(window[0]);
            Properties saved = new ProfileStore(file).load(key);
            if (!saved.getProperty("botToken").equals("token-ficticio-para-perfil") || !saved.getProperty("botRole").equals("345678"))
                throw new AssertionError("Configuração do bot preservada no perfil");
            if (saved.containsKey("password") || !saved.getProperty("calls").equals("123"))
                throw new AssertionError("Remoção opcional da senha preserva filtros sem conexão");
            Java8.writeString(data.resolve("historico.csv"), "registro fictício");
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.Timer confirm = new javax.swing.Timer(100, e -> {
                    for (Window dialog : Window.getWindows()) {
                        if (dialog instanceof JDialog && dialog.isVisible()) {
                            JOptionPane option = pane((Container)dialog);
                            if (option != null)
                                option.setValue(JOptionPane.YES_OPTION);
                        }
                    }
                });
                confirm.start();
                try {
                    invoke(window[0], "removeProfile");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    confirm.stop();
                }
            });
            if (Files.exists(file) || !Files.exists(data.resolve("historico.csv")))
                throw new AssertionError("Remover perfil preserva histórico");
            System.out.println("PASS seleção de perfil, abertura/salvamento assíncronos, segredos, filtros e "
                               + "remoção confirmada");
        } finally {
            if (window[0] != null)
                SwingUtilities.invokeAndWait(
                    () -> window[0].dispatchEvent(new WindowEvent(window[0], WindowEvent.WINDOW_CLOSING)));
            System.setProperty("user.home", previousHome);
            try (java.util.stream.Stream<Path> paths = Files.walk(home)) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
