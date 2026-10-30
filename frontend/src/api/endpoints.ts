import { http, request } from './client';
import type {
  AlertInfo,
  ApiDetail,
  ApiGroup,
  ApiSummary,
  ApiVersion,
  ApiVersionDetail,
  Approval,
  AuditLogEntry,
  CompileReport,
  ConsumerInfo,
  CurrentUser,
  Deployment,
  EnvironmentInfo,
  FlowDefinition,
  FunctionTemplateDetail,
  FunctionTemplateInfo,
  LoginResponse,
  MetricsSeries,
  PreflightCheck,
  ReleaseBundle,
  RequestLogEntry,
  RoleInfo,
  SecretInfo,
  SimulationResult,
  SuiteSummary,
  TestCase,
  TestRunSummary,
  TraceStep,
  ValidationReport,
} from './types';

/** Autentikasi dan profil. */
export const authApi = {
  /** Login anonim: tidak mengirim header Authorization. */
  login: (username: string, password: string) =>
    request<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: { username, password },
      anonymous: true,
    }),
  logout: () => http.post<{ ok: boolean }>('/api/auth/logout'),
  me: () => http.get<CurrentUser>('/api/auth/me'),
  users: () => http.get<CurrentUser[]>('/api/auth/users'),
  roles: () => http.get<RoleInfo[]>('/api/auth/roles'),
  createUser: (payload: {
    username: string;
    password: string;
    role: string;
    displayName?: string;
    email?: string;
  }) => http.post<CurrentUser>('/api/auth/users', payload),
};

/** Grup/proyek API. */
export const groupApi = {
  list: () => http.get<ApiGroup[]>('/api/groups'),
  create: (payload: { name: string; description?: string; color?: string }) =>
    http.post<ApiGroup>('/api/groups', payload),
};

/** API dan versinya. */
export const apiApi = {
  list: (params?: { groupId?: string; search?: string }) =>
    http.get<ApiSummary[]>('/api/apis', params),
  detail: (apiId: string) => http.get<ApiDetail>(`/api/apis/${apiId}`),
  create: (payload: {
    groupId?: string;
    name: string;
    path: string;
    method: string;
    description?: string;
    tags?: string;
    policyJson?: unknown;
  }) => http.post<ApiDetail>('/api/apis', payload),
  update: (apiId: string, payload: Record<string, unknown>) =>
    http.put<ApiDetail>(`/api/apis/${apiId}`, payload),
  remove: (apiId: string) => http.del<{ ok: boolean }>(`/api/apis/${apiId}`),
  openapi: (apiId: string, versionId?: string) =>
    http.get<Record<string, unknown>>(`/api/apis/${apiId}/openapi`, { versionId }),
};

/** Versi, flow, kode Java, mock, dan seed. */
export const versionApi = {
  list: (apiId: string) => http.get<ApiVersion[]>(`/api/apis/${apiId}/versions`),
  create: (apiId: string, changeNote?: string) =>
    http.post<ApiVersion>(`/api/apis/${apiId}/versions`, { changeNote }),
  detail: (versionId: string) => http.get<ApiVersionDetail>(`/api/versions/${versionId}`),
  saveFlow: (versionId: string, flow: FlowDefinition) =>
    http.put<ApiVersion & { flow: FlowDefinition }>(`/api/versions/${versionId}/flow`, { flow }),
  validate: (versionId: string, flow?: FlowDefinition) =>
    http.post<ValidationReport>(`/api/versions/${versionId}/validate`, flow ? { flow } : {}),
  saveJava: (versionId: string, javaSources: Record<string, unknown>) =>
    http.put<ApiVersion>(`/api/versions/${versionId}/java`, { javaSources }),
  compile: (versionId: string) => http.post<CompileReport>(`/api/versions/${versionId}/compile`),
  saveMocks: (versionId: string, mocks: unknown[]) =>
    http.put<ApiVersion>(`/api/versions/${versionId}/mocks`, { mocks }),
  saveSeeds: (versionId: string, seeds: unknown[]) =>
    http.put<ApiVersion>(`/api/versions/${versionId}/seeds`, { seeds }),
};

/** Simulasi dan pengujian. */
export const testApi = {
  simulate: (payload: {
    versionId: string;
    method?: string;
    path?: string;
    headers?: Record<string, string>;
    query?: Record<string, string>;
    body?: unknown;
    mocks?: unknown[];
    seed?: unknown[];
    assertions?: unknown[];
  }) => http.post<SimulationResult>('/api/simulate', payload),
  list: (apiId: string) => http.get<TestCase[]>(`/api/apis/${apiId}/tests`),
  get: (testId: string) => http.get<TestCase>(`/api/tests/${testId}`),
  create: (apiId: string, payload: Record<string, unknown>) =>
    http.post<TestCase>(`/api/apis/${apiId}/tests`, payload),
  update: (testId: string, payload: Record<string, unknown>) =>
    http.put<TestCase>(`/api/tests/${testId}`, payload),
  remove: (testId: string) => http.del<{ ok: boolean }>(`/api/tests/${testId}`),
  run: (testId: string, environment?: string) =>
    http.post<TestRunSummary>(`/api/tests/${testId}/run`, undefined, { environment }),
  runSuite: (apiId: string, versionId?: string, environment?: string) =>
    http.post<SuiteSummary>(`/api/apis/${apiId}/tests/run`, undefined, { versionId, environment }),
  history: (apiId: string) => http.get<TestRunSummary[]>(`/api/apis/${apiId}/tests/history`),
  assertionTypes: () => http.get<{ type: string }[]>('/api/tests/assertion-types'),
};

/** Publish, approval, deploy, canary, rollback. */
export const releaseApi = {
  preflight: (versionId: string, environment: string) =>
    http.get<PreflightCheck>(`/api/versions/${versionId}/preflight`, { environment }),
  requestPublish: (versionId: string, environment: string, reason?: string) =>
    http.post<Approval>(`/api/versions/${versionId}/request-publish`, { environment, reason }),
  publish: (versionId: string, environment: string, approvalId?: string) =>
    http.post<ReleaseBundle>(`/api/versions/${versionId}/publish`, { environment, approvalId }),
  approvals: (all = false) => http.get<Approval[]>('/api/approvals', { all }),
  decide: (approvalId: string, approve: boolean, note?: string) =>
    http.post<Approval>(`/api/approvals/${approvalId}/decide`, { approve, note }),
  releases: (apiId: string) => http.get<ReleaseBundle[]>(`/api/apis/${apiId}/releases`),
  deploy: (
    releaseId: string,
    environment: string,
    strategy: 'DIRECT' | 'CANARY',
    canaryPercent?: number,
  ) => http.post<Deployment>(`/api/releases/${releaseId}/deploy`, { environment, strategy, canaryPercent }),
  deployments: (apiId: string, environment?: string) =>
    http.get<Deployment[]>(`/api/apis/${apiId}/deployments`, { environment }),
  allDeployments: () => http.get<Deployment[]>('/api/deployments'),
  updateCanary: (deploymentId: string, canaryPercent: number) =>
    http.put<Deployment>(`/api/deployments/${deploymentId}/canary`, { canaryPercent }),
  rollback: (apiId: string, environment: string) =>
    http.post<Deployment>(`/api/apis/${apiId}/rollback`, { environment }),
};

/** Function template dan katalognya. */
export const templateApi = {
  list: (category?: string) => http.get<FunctionTemplateInfo[]>('/api/templates', { category }),
  categories: () => http.get<{ category: string; count: number }[]>('/api/templates/categories'),
  detail: (templateId: string) => http.get<FunctionTemplateDetail>(`/api/templates/${templateId}`),
  dependencies: (templateId: string) =>
    http.get<{ templateKey: string; usedBy: Record<string, unknown>[]; count: number }>(
      `/api/templates/${templateId}/dependencies`,
    ),
  create: (payload: Record<string, unknown>) => http.post<FunctionTemplateInfo>('/api/templates', payload),
  createVersion: (templateId: string, payload: Record<string, unknown>) =>
    http.post<Record<string, unknown>>(`/api/templates/${templateId}/versions`, payload),
};

/** Administrasi: environment, secret, consumer, audit, observability. */
export const adminApi = {
  environments: () => http.get<EnvironmentInfo[]>('/api/environments'),
  updateEnvironment: (code: string, payload: Record<string, unknown>) =>
    http.put<EnvironmentInfo>(`/api/environments/${code}`, payload),
  secrets: (environment?: string) => http.get<SecretInfo[]>('/api/secrets', { environment }),
  putSecret: (payload: { name: string; environment: string; value: string; description?: string }) =>
    http.post<SecretInfo>('/api/secrets', payload),
  deleteSecret: (secretId: string) => http.del<{ ok: boolean }>(`/api/secrets/${secretId}`),
  consumers: () => http.get<ConsumerInfo[]>('/api/consumers'),
  createConsumer: (payload: Record<string, unknown>) =>
    http.post<ConsumerInfo>('/api/consumers', payload),
  audit: (params?: { entityType?: string; entityId?: string }) =>
    http.get<AuditLogEntry[]>('/api/audit', params),
  logs: (apiId?: string) => http.get<RequestLogEntry[]>('/api/observability/logs', { apiId }),
  trace: (traceId: string) =>
    http.get<RequestLogEntry & { found: boolean; trace?: TraceStep[] }>(
      `/api/observability/trace/${traceId}`,
    ),
  metrics: (params?: { apiId?: string; environment?: string; minutes?: number }) =>
    http.get<MetricsSeries>('/api/observability/metrics', params),
  alerts: (params?: { apiId?: string; errorRate?: number; latency?: number }) =>
    http.get<AlertInfo[]>('/api/observability/alerts', params),
};
