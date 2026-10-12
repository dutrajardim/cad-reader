package br.gov.mg.sids.reader;

import java.awt.BorderLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.Normalizer;
import java.util.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;

/** Seleção pesquisável em um único campo; somente opções do catálogo são aceitas. */
final class FilterSelector extends JPanel {
    final JComboBox<Option> choices = new JComboBox<>();
    private final List<Option> all = new ArrayList<>();
    private final JTextField entry;
    private String preferredId = "";
    private boolean rebuilding, changingEditor;

    FilterSelector() {
        super(new BorderLayout());
        choices.setEditable(true);
        choices.setEditor(new BasicComboBoxEditor() {
            /** O modelo pode mudar durante uma digitação sem substituir o texto/cursor. */
            @Override
            public void setItem(Object item) {
                if (rebuilding)
                    return;
                changingEditor = true;
                try {
                    super.setItem(item);
                } finally {
                    changingEditor = false;
                }
            }
        });
        entry = (JTextField)choices.getEditor().getEditorComponent();
        entry.setToolTipText("Digite parte do nome ou ID e selecione uma opção; apague para mostrar todos");
        entry.getAccessibleContext().setAccessibleName("Selecionar filtro por nome ou ID");
        choices.setMaximumRowCount(14);
        add(choices, BorderLayout.CENTER);
        entry.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { edited(); }
            public void removeUpdate(DocumentEvent e) { edited(); }
            public void changedUpdate(DocumentEvent e) { edited(); }
            private void edited() {
                if (!changingEditor && !rebuilding)
                    rebuild();
            }
        });
        entry.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (entry.isFocusOwner())
                        entry.selectAll();
                });
            }
        });
        choices.addActionListener(e -> {
            if (rebuilding || changingEditor)
                return;
            Object selected = choices.getSelectedItem();
            Option option = selected instanceof Option ? (Option)selected : null;
            // Enter confirma um resultado único ou um nome completo, nunca texto livre.
            if (option == null) {
                for (int i = 0; i < choices.getItemCount(); i++)
                    if (choices.getItemAt(i).toString().equals(entry.getText()))
                        option = choices.getItemAt(i);
                if (option == null && choices.getItemCount() == 1)
                    option = choices.getItemAt(0);
            }
            if (option != null) {
                preferredId = option.id;
                rebuilding = true;
                try {
                    choices.setSelectedItem(option);
                } finally {
                    rebuilding = false;
                }
                choices.getEditor().setItem(option);
            }
        });
    }

    /** Restaura a seleção salva sem interpretar o nome apresentado como uma busca. */
    void setOptions(List<Option> options, String selectedId) {
        all.clear();
        all.addAll(options);
        all.sort(Comparator.comparing(o -> normalized(o.name)));
        preferredId = selectedId;
        rebuilding = true;
        try {
            DefaultComboBoxModel<Option> model = new DefaultComboBoxModel<>();
            Option selected = null;
            for (Option option : all) {
                model.addElement(option);
                if (option.id.equals(preferredId))
                    selected = option;
            }
            choices.setModel(model);
            if (selected != null)
                choices.setSelectedItem(selected);
        } finally {
            rebuilding = false;
        }
        choices.getEditor().setItem(choices.getSelectedItem());
    }

    void clear() { setOptions(Collections.emptyList(), ""); }

    /** Uma busca digitada precisa ser confirmada por escolha ou Enter. */
    String selectedId() {
        Object selected = choices.getSelectedItem();
        if (!(selected instanceof Option))
            return "";
        Option option = (Option)selected;
        return entry.getText().equals(option.toString()) ? option.id : "";
    }

    /** Reduz a lista ignorando acentos/caixa; não altera o documento em sua notificação. */
    private void rebuild() {
        String query = normalized(entry.getText().trim());
        DefaultComboBoxModel<Option> model = new DefaultComboBoxModel<>();
        Option preferred = null;
        for (Option option : all) {
            if (normalized(option.toString()).contains(query)) {
                model.addElement(option);
                if (option.id.equals(preferredId))
                    preferred = option;
            }
        }
        rebuilding = true;
        try {
            choices.setModel(model);
            if (preferred != null)
                choices.setSelectedItem(preferred);
            if (choices.isShowing() && entry.isFocusOwner())
                choices.setPopupVisible(model.getSize() > 0);
        } finally {
            rebuilding = false;
        }
        choices.setToolTipText(model.getSize() == 0 ? "Nenhum filtro encontrado"
                                                    : model.getSize() + " filtros encontrados");
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
    }
    static final class Option {
        final String id, name;
        Option(String id, String name) {
            this.id = id;
            this.name = name;
        }
        public String toString() { return name + " (" + id + ")"; }
    }
}
