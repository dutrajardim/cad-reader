package br.gov.mg.sids.reader;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.nio.file.*;
import javax.swing.*;

/** Exercita os controles reais do bot sem conectar ao CAD ou Discord. */
public final class ReaderBotWindowTest {
    static Object field(Object target,String name)throws Exception {return ReaderProfileWindowTest.field(target,name);}
    static void set(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static void check(boolean ok,String message){ReaderIntegrationTest.check(ok,message);}
    static void configure(CadReaderWindow window, boolean cancel)throws Exception {
        Timer answer=new Timer(100,e->{
            for(Window dialog:Window.getWindows()) {
                if(!dialog.isVisible() || !(dialog instanceof JDialog)) continue;
                JOptionPane pane=ReaderProfileWindowTest.pane((Container)dialog);
                if(pane==null || !(pane.getMessage() instanceof JPanel))continue;
                String[] values={cancel?"mudanca-cancelada":"token-ficticio-para-configuracao","123456","234567","345678"};
                int index=0;
                for(Component component:((JPanel)pane.getMessage()).getComponents())
                    if(component instanceof JTextField) ((JTextField)component).setText(values[index++]);
                pane.setValue(cancel?JOptionPane.CANCEL_OPTION:JOptionPane.OK_OPTION);
            }
        });
        answer.setRepeats(false); answer.start();
        ((JButton)field(window,"configureBotButton")).doClick();
    }
    public static void main(String[] args)throws Exception {
        String original=System.getProperty("user.home");
        Path home=Files.createTempDirectory("cad-bot-ui-");System.setProperty("user.home",home.toString());
        CadReaderWindow[] holder=new CadReaderWindow[1];
        try {
            SwingUtilities.invokeAndWait(()->{
                try {
                    CadReaderWindow w=holder[0]=new CadReaderWindow();w.setVisible(true);
                    check(((JButton)field(w,"configureBotButton")).isEnabled(),"Configurar disponível sem CAD");
                    check(!((JButton)field(w,"startBotButton")).isEnabled(),"Iniciar aguarda filtros CAD");
                    configure(w,false);
                    check(new String(((JPasswordField)field(w,"botToken")).getPassword()).equals("token-ficticio-para-configuracao"),"Configuração aplicada sem conectar");
                    check(field(w,"bot")==null && field(w,"reader")==null,"Configurar não abre conexão");
                    configure(w,true);
                    check(new String(((JPasswordField)field(w,"botToken")).getPassword()).equals("token-ficticio-para-configuracao"),"Cancelar preserva configuração anterior");
                    CadXmppReader reader=new CadXmppReader(ReaderOptions.parse(new String[]{"--user","fake","--password","fake"}));
                    CadSummary summary=new CadSummary(reader);
                    summary.catalog(ReaderIntegrationTest.xml("<cache xmlns='"+CadXml.CAD+"'><filtrosChamada><i id='1'><descricao>Teste</descricao></i></filtrosChamada></cache>"));
                    set(w,"reader",reader);set(w,"summary",summary);
                    ReaderProfileWindowTest.invoke(w,"refreshBotControls");
                    check(((JButton)field(w,"startBotButton")).isEnabled(),"Catálogo habilita início");
                    DiscordBot bot=new DiscordBot("token-ficticio-para-configuracao","123456","234567","",summary,message->{});
                    set(w,"bot",bot);
                    ReaderProfileWindowTest.invoke(w,"refreshBotControls");
                    check(!((JButton)field(w,"configureBotButton")).isEnabled() && ((JButton)field(w,"stopBotButton")).isEnabled(),"Bot ativo bloqueia configuração e permite parar");
                    ((JButton)field(w,"stopBotButton")).doClick();
                    check(!((JButton)field(w,"startBotButton")).isEnabled() && ((JLabel)field(w,"botState")).getText().contains("encerrando"),"Parada bloqueia reinício concorrente");
                } catch(Exception e) {throw new RuntimeException(e);}
            });
            for(int n=0;n<100;n++) {
                boolean[] stopped={false};
                SwingUtilities.invokeAndWait(()->{try{stopped[0]=field(holder[0],"bot")==null;}catch(Exception e){throw new RuntimeException(e);}});
                if(stopped[0])break;
                Thread.sleep(20);
            }
            SwingUtilities.invokeAndWait(()->{try {
                check(field(holder[0],"bot")==null,"Parada concluída");
                check(((JButton)field(holder[0],"startBotButton")).isEnabled(),"Pode reiniciar após parar");
                check(((JLabel)field(holder[0],"botState")).getText().contains("parado"),"Estado parado visível");
            }catch(Exception e){throw new RuntimeException(e);}});
            System.out.println("PASS interface bot: configuração offline, cancelar, habilitação, estado e parada assíncrona sem rede");
        } finally {
            SwingUtilities.invokeAndWait(()->{if(holder[0]!=null)holder[0].dispatchEvent(new WindowEvent(holder[0],WindowEvent.WINDOW_CLOSING));});
            System.setProperty("user.home",original);
            try(java.util.stream.Stream<Path> paths=Files.walk(home)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
    }
}
