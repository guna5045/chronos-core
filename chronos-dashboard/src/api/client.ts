import { ApiErrorResponse } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export class ApiClientError extends Error {
  public status: number;
  public code: string;
  public fieldErrors?: Record<string, string>;
  public path?: string;
  public requestId?: string;
  public traceId?: string;
  public details?: string[];

  constructor(errorResponse: ApiErrorResponse) {
    super(errorResponse.message || 'API request failed');
    this.name = 'ApiClientError';
    this.status = errorResponse.status;
    this.code = errorResponse.code;
    this.fieldErrors = errorResponse.fieldErrors;
    this.path = errorResponse.path;
    this.requestId = errorResponse.requestId;
    this.traceId = errorResponse.traceId;
    this.details = errorResponse.details;
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

  // Ensure request ID propagation for client-initiated requests
  if (!headers.has('X-Request-Id')) {
    headers.set('X-Request-Id', `client-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`);
  }

  const token = localStorage.getItem('chronos_auth_token');
  if (token && token !== 'undefined' && token !== 'null' && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(url, {
      ...options,
      headers,
    });
  } catch (err: unknown) {
    const rawError = err instanceof Error ? err.message : String(err);
    const errorData: ApiErrorResponse = {
      timestamp: new Date().toISOString(),
      status: 0,
      code: 'NETWORK_ERROR',
      message: `Unable to reach Chronos Core coordinator (${rawError}). Please verify the backend service is running on ${BASE_URL}.`,
      path: endpoint,
      requestId: headers.get('X-Request-Id') || undefined,
    };
    throw new ApiClientError(errorData);
  }

  if (!response.ok) {
    if (response.status === 401) {
      window.dispatchEvent(new CustomEvent('chronos:unauthorized'));
    }

    const respRequestId = response.headers?.get ? (response.headers.get('x-request-id') || headers.get('X-Request-Id') || undefined) : (headers.get('X-Request-Id') || undefined);
    const respTraceId = response.headers?.get ? (response.headers.get('x-trace-id') || undefined) : undefined;

    let errorData: ApiErrorResponse;
    let rawText = '';
    if (typeof response.text === 'function') {
      try {
        rawText = await response.text();
      } catch {
        rawText = '';
      }
    }

    try {
      if (rawText) {
        errorData = JSON.parse(rawText);
      } else if (typeof response.json === 'function') {
        errorData = await response.json();
      } else {
        throw new Error('No body');
      }
      if (!errorData.requestId && respRequestId) {
        errorData.requestId = respRequestId;
      }
      if (!errorData.traceId && respTraceId) {
        errorData.traceId = respTraceId;
      }
    } catch {
      // Check if this was a Vite dev proxy failure to upstream 8080 (which surfaces as 500/504 ECONNREFUSED)
      const isProxyFailure =
        rawText.includes('ECONNREFUSED') ||
        rawText.includes('connect ECONNREFUSED') ||
        rawText.includes('Proxy error') ||
        response.status === 502 ||
        response.status === 503 ||
        response.status === 504;

      if (isProxyFailure) {
        errorData = {
          timestamp: new Date().toISOString(),
          status: response.status === 500 ? 503 : response.status,
          code: 'COORDINATOR_UNREACHABLE',
          message: `Chronos Core coordinator process is unreachable (connection refused on ${BASE_URL}).`,
          path: endpoint,
          requestId: respRequestId,
          traceId: respTraceId,
        };
      } else {
        errorData = {
          timestamp: new Date().toISOString(),
          status: response.status,
          code: response.status === 403 ? 'FORBIDDEN' : (response.status === 404 ? 'NOT_FOUND' : 'HTTP_ERROR'),
          message: rawText && rawText.length < 200 ? rawText : (response.statusText || 'An unexpected server error occurred'),
          path: endpoint,
          requestId: respRequestId,
          traceId: respTraceId,
        };
      }
    }
    throw new ApiClientError(errorData);
  }

  if (response.status === 204) {
    return {} as T;
  }

  return response.json();
}
