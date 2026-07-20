// Version injected at build time via Vite define
// Falls back to 'dev' in development
export const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev';
