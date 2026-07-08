import { Injectable } from '@angular/core';

interface RuntimeEnv {
  API_BASE_URL?: string;
  WS_BASE_URL?: string;
}

declare global {
  interface Window {
    __env?: RuntimeEnv;
  }
}

// Falls back to localhost defaults so `ng serve` works without env.js (only
// present in the built/nginx-served app — see env.template.js).
@Injectable({ providedIn: 'root' })
export class RuntimeConfigService {
  readonly apiBaseUrl = window.__env?.API_BASE_URL || 'http://localhost:8080';
  readonly wsBaseUrl = window.__env?.WS_BASE_URL || 'ws://localhost:3000';
}
