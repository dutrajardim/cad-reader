package br.gov.mg.sids.reader;

import java.time.Instant;
import java.util.*;

/** Observação passiva das requisições CAD: não acessa a rede nem guarda XML/credenciais. */
final class CadCommunication {
    private final LinkedHashMap<String,Map<String,Object>> requests = new LinkedHashMap<>();
    private final Map<String,Long> operations = new LinkedHashMap<>();
    private long revision, total, completed, rejected, timeouts, sendErrors, interrupted, notifications, elapsedTotal, sessions;
    synchronized long version() { return revision; }
    synchronized void beginSession() {
        for(Map<String,Object> row:requests.values()) if("pending".equals(row.get("outcome"))) {
            row.put("outcome","interrupted"); interrupted++;
        }
        sessions++; revision++;
    }
    synchronized void request(String key, String operation, String callId, int page, String outcome, long elapsedMillis) {
        Map<String,Object> row=requests.get(key);
        if(outcome.equals("started")) {
            if(row!=null)return;
            row=new LinkedHashMap<>(Java8.map("id",key,"operation",operation,"callId",callId,"page",page,
                "at",Instant.now().toString(),"outcome","pending","elapsedMillis",null));
            requests.put(key,row); total++; operations.merge(operation,1L,Long::sum);
        } else {
            if(row==null || !"pending".equals(row.get("outcome")))return;
            row.put("outcome",outcome); row.put("elapsedMillis",Math.max(0,elapsedMillis));
            switch(outcome) {
            case "ok": completed++;elapsedTotal+=Math.max(0,elapsedMillis);break;
            case "rejected": rejected++;break;
            case "timeout": timeouts++;break;
            case "send_error": sendErrors++;break;
            default: interrupted++;break;
            }
        }
        while(requests.size()>300) {
            String oldest=null;
            for(Map.Entry<String,Map<String,Object>> entry:requests.entrySet()) if(!"pending".equals(entry.getValue().get("outcome"))){oldest=entry.getKey();break;}
            if(oldest==null)break;
            requests.remove(oldest);
        }
        revision++;
    }
    synchronized void notification(){notifications++;revision++;}
    synchronized Map<String,Object> snapshot() {
        List<Object> rows=new ArrayList<>();
        for(Map<String,Object> row:requests.values())rows.add(new LinkedHashMap<>(row));
        Collections.reverse(rows);
        return Java8.map("revision",revision,"sessions",sessions,"total",total,"completed",completed,
            "rejected",rejected,"timeouts",timeouts,"sendErrors",sendErrors,"interrupted",interrupted,
            "pending",total-completed-rejected-timeouts-sendErrors-interrupted,"notifications",notifications,
            "averageMillis",completed==0?null:elapsedTotal/completed,"operations",new LinkedHashMap<>(operations),"requests",rows);
    }
}
