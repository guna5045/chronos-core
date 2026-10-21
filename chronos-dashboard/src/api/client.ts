import { ApiErrorResponse } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export class ApiClientError extends Error {
  public status: number;
  public code: string;
  public fieldErrors?: Record<string, string>;
  public path?: string;

  constructor(errorResponse: ApiErrorResponse) {
    super(errorResponse.message || 'API request failed');
    this.name = 'ApiClientError';
    this.status = errorResponse.status;
    this.code = errorResponse.code;
    this.fieldErrors = errorResponse.fieldErrors;
    this.path = errorResponse.path;
  }
}

export async function request<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const url = `${BASE_URL}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;

  const headers = new Headers(options.headers || {});
  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const token = localStorage.getItem('chronos_auth_token');
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(url, {
    ...options,
    headers,
  });

  if (!response.ok) {
    if (response.status === 401) {
      window.dispatchEvent(new CustomEvent('chronos:unauthorized'));
    }

    let errorData: ApiErrorResponse;
    try {
      errorData = await response.json();
    } catch {
      errorData = {
        timestamp: new Date().toISOString(),
        status: response.status,
        code: response.status === 403 ? 'FORBIDDEN' : 'HTTP_ERROR',
        message: response.statusText || 'An unexpected server error occurred',
        path: endpoint,
      };
    }
    throw new ApiClientError(errorData);
  }

  if (response.status === 204) {
    return {} as T;
  }

  return response.json();
}
