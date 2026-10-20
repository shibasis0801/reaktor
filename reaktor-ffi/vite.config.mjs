import { hermesLibrary, webContract } from '../dependeasy/javascript/api/vite.ts';
export default hermesLibrary(webContract(new URL(import.meta.url)), 'src/commonMain/typescript/index.ts', 'reaktor-ffi.js');
