package br.gov.mg.sids.reader;
import java.lang.reflect.*;
import java.util.concurrent.*;
import javax.swing.*;
/** Teste visual local, sem conexão externa: lote de 600 eventos e seleção preservada. */
public class ReaderWindowTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                CadReaderWindow w = new CadReaderWindow();
                try {
                    w.setVisible(true);
                    Field f = CadReaderWindow.class.getDeclaredField("pendingEvents");
                    f.setAccessible(true);
                    ArrayBlockingQueue<CadMonitor.Event> queue =
                        (ArrayBlockingQueue<CadMonitor.Event>) f.get(w);
                    for (int i = 0; i < 600; i++)
                        queue.add(new CadMonitor.Event("1", "AB-01", "" + i, "" + i, "x", "ATUALIZAÇÃO",
                            "body", "", "2026-10-11T12:00:00Z"));
                    Method flush = CadReaderWindow.class.getDeclaredMethod("flushDisplay");
                    flush.setAccessible(true);
                    flush.invoke(w);
                    Field t = CadReaderWindow.class.getDeclaredField("table");
                    t.setAccessible(true);
                    JTable table = (JTable) t.get(w);
                    if (table.getRowCount() != 250)
                        throw new AssertionError("Primeiro lote");
                    table.setRowSelectionInterval(0, 0);
                    flush.invoke(w);
                    if (table.getRowCount() != 500 || table.getSelectedRow() != 0)
                        throw new AssertionError("Lote e seleção");
                    flush.invoke(w);
                    if (table.getRowCount() != 600)
                        throw new AssertionError("Último lote");
                } finally {
                    w.dispatchEvent(
                        new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING));
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        System.out.println("PASS tabela em lotes e seleção preservada durante atualizações");
    }
}
