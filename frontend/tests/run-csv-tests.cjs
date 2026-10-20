const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const {execFileSync}=require('node:child_process');
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'cad-csv-test-'));
try{
 const root=path.resolve(__dirname,'..');
 execFileSync(process.execPath,[require.resolve('typescript/bin/tsc'),'src/csv.ts','src/callFilters.ts','src/types.ts','src/assignmentState.ts','src/pollVisible.ts','src/api.ts','src/resourceSummary.ts','src/areaSummary.ts','src/mapView.ts','src/baseMarker.ts','--outDir',temp,'--module','commonjs','--target','ES2022','--lib','ES2022,DOM','--strict','--skipLibCheck'],{cwd:root,stdio:'inherit'});
 execFileSync(process.execPath,[path.join(__dirname,'csv.test.cjs')],{stdio:'inherit',env:{...process.env,CAD_CSV_MODULE:path.join(temp,'csv.js'),CAD_FILTER_MODULE:path.join(temp,'callFilters.js')}});
 execFileSync(process.execPath,[path.join(__dirname,'filters.test.cjs')],{stdio:'inherit',env:{...process.env,CAD_FILTER_MODULE:path.join(temp,'callFilters.js')}});
 execFileSync(process.execPath,[path.join(__dirname,'performance.test.cjs')],{stdio:'inherit',env:{...process.env,CAD_WEB_TEST_MODULES:temp}});
 execFileSync(process.execPath,[path.join(__dirname,'dashboard.test.cjs')],{stdio:'inherit',env:{...process.env,CAD_WEB_TEST_MODULES:temp}});
}finally{fs.rmSync(temp,{recursive:true,force:true})}
