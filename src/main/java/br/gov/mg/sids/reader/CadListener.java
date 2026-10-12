package br.gov.mg.sids.reader;

import org.w3c.dom.Element;

/** Recebe dados do reader sem depender do console, Swing ou Discord. Chamadas ocorrem na thread CAD. */
public interface CadListener {
    default void onLog(String category, String text) {
    }
    default void onFilters(Element cache) {
    }
    /** Sinal local de conclusão da consulta paginada; não envia nada ao CAD. */
    default void onHistoryFinished(String callId, boolean complete) {
    }
    default void onMessage(Element message) {
    }
}
