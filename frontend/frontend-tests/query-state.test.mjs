import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { after, test } from 'node:test';
import { computed, effectScope } from 'vue';
import { createOrderCancellation } from '../src/utils/orderCancellation.ts';

const hooks = registerHooks({resolve(specifier, context, next) {
  if (specifier.startsWith('@/')) return {url:new URL(`../src/${specifier.slice(2)}.ts`,import.meta.url).href,shortCircuit:true};
  return next(specifier,context);
}});
const {useQuery} = await import('../src/composables/useQuery.ts');
after(()=>hooks.deregister());
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};

test('successful cancellation updates active filter totals and pagination without refetch',async()=>{
  const scope=effectScope();
  try {
    const query=scope.run(()=>useQuery(async()=>[{id:1,status:1}])); await query.refresh();
    const active=computed(()=>query.data.value.filter(row=>row.status===1));
    const pages=computed(()=>Math.ceil(active.value.length/10));
    assert.equal(active.value.length,1);assert.equal(pages.value,1);
    await createOrderCancellation(async()=>{})(query.data.value[0]);
    assert.equal(active.value.length,0);assert.equal(pages.value,0);
  } finally {scope.stop();}
});
test('newer refresh wins and an old failure cannot erase the successful result',async()=>{
  const first=deferred(),second=deferred(),scope=effectScope();let calls=0;
  try {
    const query=scope.run(()=>useQuery(()=> (++calls===1?first:second).promise));
    const old=query.refresh(),current=query.refresh(); second.resolve([{id:2}]); await current;
    first.reject(new Error('old request failed'));await old;
    assert.deepEqual(query.data.value,[{id:2}]);assert.equal(query.error.value,'');assert.equal(query.loading.value,false);
  }finally{scope.stop();}
});
test('a response after leaving the page cannot repopulate its query state',async()=>{
  const response=deferred(),scope=effectScope(); const query=scope.run(()=>useQuery(()=>response.promise));
  const pending=query.refresh();scope.stop();response.resolve([{id:1}]);await pending;assert.equal(query.data.value,null);
});
