import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { after, beforeEach, test } from 'node:test';
import { AxiosError } from 'axios';
import router, { messages, redirects } from './fixtures/request-browser.mjs';

const requestUrl = new URL('../src/utils/request.ts', import.meta.url).href;
const browserUrl = new URL('./fixtures/request-browser.mjs', import.meta.url).href;
const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === '@/router' || specifier === 'element-plus') {
      return { url: browserUrl, shortCircuit: true };
    }
    if (specifier.startsWith('@/')) {
      return { url: new URL(`../src/${specifier.slice(2)}.ts`, import.meta.url).href, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url === requestUrl) {
      // Vite supplies import.meta.env in the browser; Node loads the actual TS module.
      return {
        format: 'module-typescript',
        source: `import.meta.env = { VITE_API_BASE_URL: '/api' };\n${readFileSync(new URL(url), 'utf8')}`,
        shortCircuit: true
      };
    }
    return nextLoad(url, context);
  }
});

const storage = new Map();
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
});

const { default: http, request } = await import('../src/utils/request.ts');
const { loginApi } = await import('../src/api/auth.ts');
const { getToken, setToken } = await import('../src/utils/auth.ts');
after(() => hooks.deregister());

beforeEach(() => {
  messages.length = 0;
  redirects.length = 0;
  storage.clear();
  router.currentRoute.value = { path: '/login', fullPath: '/login' };
});

function respond(status, data) {
  http.defaults.adapter = async config => {
    const response = { status, statusText: '', data, config, headers: {} };
    if (status >= 400) {
      throw new AxiosError(`Request failed with status code ${status}`, AxiosError.ERR_BAD_REQUEST, config, undefined, response);
    }
    return response;
  };
}

const credentials = { username: 'test-user', password: 'wrong-password' };
const loginFailure = { code: 401, message: '用户名或密码错误', data: null };

test('first login with a wrong password displays the backend credential error', async () => {
  respond(401, loginFailure);

  await assert.rejects(loginApi(credentials), error => error.response.status === 401);

  assert.deepEqual(messages, ['用户名或密码错误']);
  assert.equal(getToken(), null);
  assert.deepEqual(redirects, []);
});

test('login HTTP 401 preserves an existing token and does not redirect', async () => {
  setToken('existing-token');
  router.currentRoute.value = { path: '/dashboard', fullPath: '/dashboard?roomId=2' };
  respond(401, loginFailure);

  await assert.rejects(loginApi(credentials));

  assert.equal(getToken(), 'existing-token');
  assert.deepEqual(redirects, []);
  assert.deepEqual(messages, ['用户名或密码错误']);
});

test('login business-code 401 preserves the session and reports credentials', async () => {
  setToken('existing-token');
  router.currentRoute.value = { path: '/dashboard', fullPath: '/dashboard?roomId=2' };
  respond(200, loginFailure);

  await assert.rejects(loginApi(credentials), error => error === loginFailure);

  assert.equal(getToken(), 'existing-token');
  assert.deepEqual(redirects, []);
  assert.deepEqual(messages, ['用户名或密码错误']);
});

for (const status of [401, 200]) {
  test(`protected request with HTTP ${status} and code 401 clears token and preserves return path`, async () => {
    setToken('expired-token');
    router.currentRoute.value = { path: '/dashboard', fullPath: '/dashboard?roomId=2#bookings' };
    respond(status, { code: 401, message: '登录已过期，请重新登录', data: null });

    await assert.rejects(request({ url: '/users/me', method: 'get' }));

    assert.equal(getToken(), null);
    assert.deepEqual(redirects, [{ path: '/login', query: { redirect: '/dashboard?roomId=2#bookings' } }]);
    assert.deepEqual(messages, ['登录已过期，请重新登录']);
  });
}

test('login HTTP 401 without backend text uses a credential error fallback', async () => {
  respond(401, { code: 401, message: '', data: null });

  await assert.rejects(loginApi(credentials));

  assert.deepEqual(messages, ['用户名或密码错误']);
  assert.deepEqual(redirects, []);
});

test('the login URL is exempt from session expiry only for a POST login attempt', async () => {
  setToken('expired-token');
  router.currentRoute.value = { path: '/dashboard', fullPath: '/dashboard' };
  respond(401, loginFailure);

  await assert.rejects(request({ url: '/users/login', method: 'get' }));

  assert.equal(getToken(), null);
  assert.deepEqual(redirects, [{ path: '/login', query: { redirect: '/dashboard' } }]);
  assert.deepEqual(messages, ['登录已过期，请重新登录']);
});

for (const status of [200, 401]) {
  test(`CSV request with HTTP ${status} and JSON Blob 401 still expires the session`, async () => {
    setToken('expired-token');
    router.currentRoute.value = { path: '/admin/bookings', fullPath: '/admin/bookings' };
    respond(status, new Blob([JSON.stringify({code:401,message:'登录已过期',data:null})],{type:'application/json'}));
    await assert.rejects(request({url:'/orders/export',responseType:'blob'}));
    assert.equal(getToken(),null);
    assert.deepEqual(redirects,[{path:'/login',query:{redirect:'/admin/bookings'}}]);
  });
}
test('CSV forbidden JSON error is decoded without clearing a valid session', async () => {
  setToken('valid-token'); respond(403,new Blob([JSON.stringify({code:403,message:'无导出权限'})],{type:'application/json'}));
  await assert.rejects(request({url:'/orders/export',responseType:'blob'}));
  assert.deepEqual(messages,['无导出权限']); assert.equal(getToken(),'valid-token');
});
test('admin actions use the existing real API methods, request bodies and CSV negotiation', async () => {
  const rooms = await import('../src/api/rooms.ts'), orders = await import('../src/api/orders.ts'), outbox = await import('../src/api/outbox.ts');
  const calls = [];
  http.defaults.adapter = async config => { calls.push(config); return {status:200,statusText:'',config,headers:{},data:config.responseType==='blob'?new Blob(['id\n1'],{type:'text/csv'}):{code:200,message:'ok',data:[]}}; };
  const room = {roomName:'Room',capacity:10,equipment:['Whiteboard'],status:1};
  await rooms.createRoomApi(room); await rooms.updateRoomApi(9,room); await rooms.deleteRoomApi(9);
  await orders.getAllOrdersApi(); await orders.exportOrdersApi(); await outbox.getOutboxApi();
  assert.deepEqual(calls.map(x=>[x.method,x.url]),[['post','/rooms'],['put','/rooms/9'],['delete','/rooms/9'],['get','/orders'],['get','/orders/export'],['get','/admin/messages/outbox']]);
  assert.deepEqual(JSON.parse(calls[0].data),room); assert.equal(calls[4].responseType,'blob'); assert.equal(calls[4].headers.Accept,'text/csv');
});
