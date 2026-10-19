package br.gov.mg.sids.reader;

import java.time.Instant;
import java.util.*;
import org.w3c.dom.Element;

/** Cache limitado dos dados que já chegaram ao leitor. Consultas explícitas da Web usam a fila de leitura do CAD. */
final class CadCallDetails {
    private static final int MAX_CALLS=50, MAX_HISTORY=200, MAX_PEOPLE=100, MAX_TEXT=8000, MAX_CHARS=4000000;
    private final LinkedHashMap<String,Detail> calls=new LinkedHashMap<>(16,.75f,true);
    private long revision;
    private static final class Section {
        final Map<String,Map<String,Object>> items=new LinkedHashMap<>();
        String status="not_received",updated="",source="";
        Integer expected;
        int received;
        boolean truncated,verified;
    }
    private static final class Detail { final Section history=new Section(),people=new Section(); }
    synchronized long version(){return revision;}
    synchronized void reset(){calls.clear();revision++;}
    private Detail detail(String id){return calls.computeIfAbsent(id,key->new Detail());}
    synchronized void request(String callId,String operation,int page,String outcome){
        if(callId.isEmpty() || !(operation.equals("envRecuperarResumosHistoricosChamada")||operation.equals("envRecuperarEnvolvidosChamada")))return;
        Detail d=detail(callId);Section section=operation.equals("envRecuperarEnvolvidosChamada")?d.people:d.history;
        if(outcome.equals("started")) {
            if(page==1){section.received=0;section.expected=null;section.verified=false;}
            section.status="loading";section.source="query";
        } else if(!outcome.equals("ok"))section.status=outcome.equals("interrupted")?"interrupted":"incomplete";
        section.updated=Instant.now().toString();revision++;trim();
    }
    synchronized void accept(Element message){
        String tag=message.getLocalName(),id=message.getAttribute("readerCallId");
        if(tag.equals("rtnRecuperarResumosHistoricosChamada")&&!id.isEmpty()) {
            read(detail(id).history,message,"resumosHistoricosChamada","resumoHistoricoChamada",false,true);revision++;trim();
        } else if(tag.equals("rtnRecuperarEnvolvidosChamada")&&!id.isEmpty()) {
            read(detail(id).people,message,"envolvidosChamada","envolvidoChamada",true,true);revision++;trim();
        }
    }
    synchronized void summary(String callId,Element call){
        List<Element> rows=CadXml.collectionItems(call,"resumosHistoricosChamada","resumoHistoricoChamada");
        if(rows.isEmpty())return;
        Section section=detail(callId).history;
        read(section,call,"resumosHistoricosChamada","resumoHistoricoChamada",false,false);revision++;trim();
    }
    private void read(Section section,Element message,String collection,String tag,boolean people,boolean query){
        List<Element> rows=CadXml.collectionItems(message,collection,tag);
        if(query&&"1".equals(message.getAttribute("readerQueryPage"))){section.items.clear();section.truncated=false;}
        boolean changed=false;
        for(Element item:rows){
            String id=item.getAttribute("id");if(id.isEmpty())id=CadXml.text(item,people?"idEnvolvidoChamada":"idHistoricoChamada");
            Map<String,Object> value;
            if(people)value=Java8.map("name",limited(CadXml.text(item,"nome"),section),"phone",limited(CadXml.text(item,"telefone"),section),"requester",CadXml.text(item,"solicitante"));
            else value=Java8.map("date",CadXml.text(item,"dataHoraInclusao"),"text",limited(CadXml.text(item,"descricao"),section),"type",CadXml.text(item,"tipoHistoricoChamada"),"agency",CadXml.text(item,"orgao"));
            if(id.isEmpty())id=AppFiles.hash(BotJson.write(value));
            Map<String,Object> row=new LinkedHashMap<>(value);row.put("id",id);
            if(section.items.size()<(people?MAX_PEOPLE:MAX_HISTORY)||section.items.containsKey(id)){if(!row.equals(section.items.put(id,row)))changed=true;}else section.truncated=true;
        }
        if(query){
            section.received+=rows.size();section.source="query";
            try{section.expected=Integer.valueOf(CadXml.nestedText(message,"paginationBean","totalSize"));}catch(NumberFormatException ignored){}
        }else if(changed){
            section.verified=false;
            if(!section.status.equals("loading"))section.status="partial";
            section.source=section.source.isEmpty()?"summary":section.source.equals("query")?"mixed":section.source;
        }
        if(!query&&section.status.equals("not_received"))section.status="partial";
        section.updated=Instant.now().toString();
    }
    private String limited(String value,Section section){if(value.length()<=MAX_TEXT)return value;section.truncated=true;return value.substring(0,MAX_TEXT);}
    synchronized void finished(String callId,boolean people,boolean complete){
        Detail d=calls.get(callId);if(d==null)return;Section s=people?d.people:d.history;
        s.verified=complete&&s.expected!=null&&s.expected>=0&&s.received>=s.expected;
        s.status=!complete?"incomplete":s.verified?"received":"partial";s.updated=Instant.now().toString();revision++;
    }
    synchronized void disconnected(){for(Detail d:calls.values())for(Section s:Java8.list(d.history,d.people))if(s.status.equals("loading"))s.status="interrupted";revision++;}
    private Map<String,Object> section(Section s){return Java8.map("status",s.status,"source",s.source,"updatedAt",s.updated,"expected",s.expected,
        "received",s.received,"verified",s.verified,"truncated",s.truncated,"items",new ArrayList<>(s.items.values()));}
    synchronized Map<String,Object> get(String id){Detail d=calls.get(id);return Java8.map("history",section(d==null?new Section():d.history),"people",section(d==null?new Section():d.people));}
    private void trim(){
        while(calls.size()>MAX_CALLS)calls.remove(calls.keySet().iterator().next());
        long chars=0;for(Detail d:calls.values())for(Section s:Java8.list(d.history,d.people))for(Map<String,Object> row:s.items.values())for(Object value:row.values())if(value instanceof String)chars+=((String)value).length();
        while(chars>MAX_CHARS&&!calls.isEmpty()){
            Detail d=calls.remove(calls.keySet().iterator().next());for(Section s:Java8.list(d.history,d.people))for(Map<String,Object> row:s.items.values())for(Object value:row.values())if(value instanceof String)chars-=((String)value).length();
        }
    }
}
