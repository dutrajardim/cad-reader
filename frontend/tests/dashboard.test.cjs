const assert=require('node:assert/strict'),path=require('node:path');const dir=process.env.CAD_WEB_TEST_MODULES;
const {resourceSummary}=require(path.join(dir,'resourceSummary.js')),{areaSummary}=require(path.join(dir,'areaSummary.js'));
const r=(status,restriction='',assignments=[])=>({status,restriction,assignments});
const summary=resourceSummary([r('D'),r('DISPONIVEL','Sem equipe'),r('E'),r('I'),r('X'),r('D','',[{status:'NL'}]),r('D','',[{status:'TR'}])]);
assert.deepEqual(summary,{total:7,available:3,assigned:2,unavailable:1,other:1,restricted:1,availableUnrestricted:2,availableRestricted:1});console.log('PASS resumo de viaturas: categorias exclusivas, restrições e prioridade de empenho ativo');
assert.deepEqual(areaSummary([{areas:[{id:'7',name:'Contagem'},{id:'7',name:'Contagem'},{id:'8',name:'Betim'}]},{areas:[{id:'7',name:'Contagem'}]},{areas:[]}]),[{label:'Contagem (7)',count:2},{label:'Área BM não informada',count:1},{label:'Betim (8)',count:1}]);console.log('PASS áreas BM: deduplica por ocorrência e mantém ausência explícita');

const mixed=resourceSummary([r('D','  '),r('D','Sem equipe'),r('D','Restrita',[{status:'NL'}]),r('I','Oficina')]);
assert.equal(mixed.availableUnrestricted,1);assert.equal(mixed.availableRestricted,1);assert.equal(mixed.restricted,3);assert.equal(mixed.assigned,1);assert.equal(mixed.available,mixed.availableUnrestricted+mixed.availableRestricted);
console.log('PASS disponíveis: separa com/sem restrição, ignora espaços e preserva prioridade do empenho');
