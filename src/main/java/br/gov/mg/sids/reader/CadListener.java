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
    /** Metadados passivos. started/ok/rejected/timeout/send_error/interrupted; nunca contém XML. */
    default void onCadRequest(String id, String operation, String callId, int page, String outcome, long elapsedMillis) {}
    default void onCadNotification(String operation) {}
    default void onParticipantsFinished(String callId, boolean complete) {}
    default void onMessage(Element message) {
    }
}
