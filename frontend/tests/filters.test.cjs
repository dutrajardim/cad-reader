const assert=require('node:assert/strict');
const {filterCalls,groupOptions,natureOptions,matchingNatures,groupColor,natureGroup,assignmentLabel}=require(process.env.CAD_FILTER_MODULE);
function test(name,run){run();console.log('PASS mapa: '+name)}
const blank={address:'Rua de Teste',status:'A',lat:-19.9,lon:-44.1,updatedAt:'',assignedResources:[]};
const calls=[
 {...blank,id:'1',number:'101',nature:'A01 Árvore · S02 Salvamento',natures:[{code:'A01',description:'Árvore'},{code:'S02',description:'Salvamento'}],assignmentStatus:'linked',assignedResources:[{resourceId:'7',prefix:'ABTS 101',status:'DP'}]},
 {...blank,id:'2',number:'102',nature:'I01 Incêndio',natures:[{code:'I01',description:'Incêndio'}],assignmentStatus:'unlinked'},
 {...blank,id:'3',number:'103',nature:'S03 Salvamento em altura',natures:[{code:'S03',description:'Salvamento em altura'}],assignmentStatus:'reported'},
 {...blank,id:'4',number:'104',nature:'Natureza não informada',natures:[],assignmentStatus:'unknown',lat:null,lon:null},
];
const all={search:'',group:'all',nature:'all',assignment:'all'};
test('Grupo usa primeira letra e considera todas as naturezas da chamada',()=>{assert.deepEqual(filterCalls(calls,{...all,group:'S'}).map(c=>c.id),['1','3']);assert.equal(natureGroup({code:' s123 ',description:''}),'S');});
test('Natureza específica e grupo precisam casar no mesmo item',()=>{assert.deepEqual(filterCalls(calls,{...all,nature:'S02'}).map(c=>c.id),['1']);assert.equal(filterCalls(calls,{...all,group:'A',nature:'S02'}).length,0);});
test('Busca é sem acentos, inclui prefixo da viatura e combina com os filtros',()=>{assert.equal(filterCalls(calls,{...all,search:'arvore'})[0].id,'1');assert.equal(filterCalls(calls,{...all,search:'ABTS 101'})[0].id,'1');assert.equal(filterCalls(calls,{...all,search:'ABTS 101',group:'I'}).length,0);});
test('Vínculos distinguem identificado, informado sem identificação e ausência observada',()=>{for(const c of calls)assert.deepEqual(filterCalls(calls,{...all,assignment:c.assignmentStatus}).map(x=>x.id),[c.id]);assert.match(assignmentLabel(calls[2]),/não identificada/);});
test('Opções de natureza acompanham o grupo e contagens não duplicam a ocorrência',()=>{assert.deepEqual(natureOptions(calls,'S').map(n=>n.code),['S02','S03']);assert.deepEqual(Object.fromEntries(groupOptions(calls)),{'?':1,A:1,I:1,S:2});});
test('Código e cor correspondem à natureza que passou no filtro',()=>{const n=matchingNatures(calls[0],{group:'S',nature:'all'})[0];assert.equal(n.code,'S02');assert.equal(groupColor('S'),groupColor('S'));assert.notEqual(groupColor('A'),groupColor('I'));});
test('Chamadas sem natureza/coordenadas continuam pesquisáveis',()=>{assert.equal(filterCalls(calls,all).length,4);assert.equal(filterCalls(calls,{...all,group:'?'})[0].id,'4');assert.equal(filterCalls(calls,{...all,search:'104'})[0].id,'4');});
test('Filtragem não altera os dados CAD recebidos',()=>{const before=JSON.stringify(calls);filterCalls(calls,{...all,group:'S'});natureOptions(calls,'all');assert.equal(JSON.stringify(calls),before);});

test('Seleções múltiplas combinam opções e preservam correspondência na mesma natureza',()=>{assert.deepEqual(filterCalls(calls,{...all,group:['S','I'],assignment:['linked','unlinked']}).map(c=>c.id),['1','2']);assert.deepEqual(filterCalls(calls,{...all,nature:['S02','I01']}).map(c=>c.id),['1','2']);assert.equal(filterCalls(calls,{...all,group:['A','I'],nature:['S02']}).length,0);assert.equal(filterCalls(calls,{search:'',group:[],nature:[],assignment:[]}).length,4);assert.deepEqual(natureOptions(calls,['A','I']).map(n=>n.code),['A01','I01']);});
