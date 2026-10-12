package br.gov.mg.sids.reader;

import java.util.*;
import org.w3c.dom.*;

/** Mesma regra de FiltroRecurso.avaliar: unidade incluída e tipo opcionalmente restrito. */
final class CadResourceFilter {
    final String id;
    final Set<String> units, types;

    CadResourceFilter(String id, Set<String> units, Set<String> types) {
        this.id = id;
        this.units = Set.copyOf(units);
        this.types = Set.copyOf(types);
    }
    static CadResourceFilter from(Element filter) {
        return new CadResourceFilter(
            filter.getAttribute("id"), ids(filter, "idsUnidades"), ids(filter, "idsTiposRecurso"));
    }
    private static Set<String> ids(Element filter, String collection) {
        Set<String> result = new HashSet<>();
        for (Element item : CadXml.collectionItems(filter, collection, "i")) {
            String value = item.getTextContent().trim();
            if (value.matches("[0-9]+"))
                result.add(value);
        }
        return result;
    }
    boolean matches(Element resource) {
        return matches(CadXml.text(resource, "idUnidadeServico"), CadXml.text(resource, "idTipoRecurso"));
    }
    boolean matches(String unit, String type) {
        return !type.isBlank() && units.contains(unit) && (types.isEmpty() || types.contains(type));
    }
}
