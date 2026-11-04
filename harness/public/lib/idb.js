const NAME = 'foldersync-harness';
let dbp;
export function db() {
  dbp ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(NAME, 1);
    req.onupgradeneeded = () => {
      for (const s of ['handles', 'jobs', 'events']) req.result.createObjectStore(s);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbp;
}
const tx = async (store, mode, fn) => {
  const d = await db();
  return new Promise((resolve, reject) => {
    const t = d.transaction(store, mode);
    const r = fn(t.objectStore(store));
    t.oncomplete = () => resolve(r?.result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
};
export const idbGet = (store, key) => tx(store, 'readonly', (s) => s.get(key));
export const idbPut = (store, key, value) => tx(store, 'readwrite', (s) => s.put(value, key));
export const idbDel = (store, key) => tx(store, 'readwrite', (s) => s.delete(key));
export const idbAll = (store) => tx(store, 'readonly', (s) => s.getAll());
export const idbClear = (store) => tx(store, 'readwrite', (s) => s.clear());
