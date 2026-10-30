/**
 * Tipe kontrak Control Plane.
 *
 * Semua tipe di sini mencerminkan bentuk respons JSON backend. Perubahan pada
 * backend harus disertai perubahan tipe agar kesalahan tertangkap saat
 * typecheck, bukan saat runtime.
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export type NodeType =
  | 'TRIGGER'
  | 'SET_VARIABLE'
  | 'TRANSFORM'
  | 'CONDITION'
  | 'SWITCH'
  | 'LOOP'
  | 'PARALLEL'
  | 'TRY_CATCH'
  | 'HTTP_CALL'
  | 'DATABASE_QUERY'
  | 'TEMPLATE_CALL'
  | 'JAVA_CODE'
  | 'RESPONSE'
  | 'LOG'
  | 'DELAY'
  | 'FAIL';

export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
export type DeploymentStatus = 'PENDING' | 'ACTIVE' | 'DRAINING' | 'INACTIVE' | 'FAILED';
export type DeploymentStrategy = 'DIRECT' | 'CANARY';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type RunStatus = 'PASSED' | 'FAILED' | 'ERROR';
export type TraceStatus = 'OK' | 'ERROR' | 'RETRIED' | 'DISABLED' | 'SKIPPED';

export interface Position {
  x: number;
  y: number;
}

/** Kebijakan error per node (BRD FR-FLOW-06). */
export interface ErrorPolicy {
  timeoutMs?: number | null;
  retries?: number | null;
  backoffMs?: number | null;
  onError?: 'FAIL' | 'CONTINUE' | 'FALLBACK';
  fallbackNodeId?: string | null;
}

export interface FlowNode {
  id: string;
  type: NodeType;
  label: string;
  config: Record<string, unknown>;
  position: Position;
  onError?: ErrorPolicy | null;
  disabled?: boolean;
}

export interface FlowEdge {
  id?: string | null;
  from: string;
  to: string;
  fromPort: string;
  label?: string | null;
}

export interface FlowDefinition {
  id?: string | null;
  name: string;
  nodes: FlowNode[];
  edges: FlowEdge[];
  version?: number;
}

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  role: string;
  roleLabel: string;
  permissions: string[];
  email?: string | null;
  enabled?: boolean;
  systemUser?: boolean;
  createdAt?: string;
  lastLoginAt?: string | null;
}

export interface LoginResponse {
  token: string;
  user: CurrentUser;
}

export interface RoleInfo {
  name: string;
  label: string;
  permissions: string[];
}

export interface ApiPolicy {
  auth?: 'none' | 'apiKey' | 'oauth2' | 'jwt' | 'hmac';
  rateLimitPerMinute?: number;
  ipWhitelist?: string[];
  cors?: { enabled: boolean; origins: string[] };
}

export interface ApiGroup {
  id: string;
  name: string;
  description?: string | null;
  color?: string | null;
  apiCount?: number;
  createdAt?: string;
}

export interface FlowSummary {
  nodes: number;
  edges: number;
  hasTrigger: boolean;
  hasResponse: boolean;
}

export interface ApiVersion {
  id: string;
  apiId: string;
  versionNumber: number;
  label: string;
  status: VersionStatus;
  changeNote?: string | null;
  createdAt: string;
  createdBy?: string | null;
  publishedAt?: string | null;
  publishedBy?: string | null;
  flowSummary?: FlowSummary;
}

export interface JavaSource {
  source: string;
  libraries?: unknown[];
}

export interface ApiVersionDetail extends ApiVersion {
  flow: FlowDefinition;
  javaSources: Record<string, JavaSource>;
  mocks: unknown[];
  seeds: unknown[];
  bytecode: Record<string, { className?: string; classCount?: number; libraries?: number }>;
}

export interface ApiSummary {
  id: string;
  groupId?: string | null;
  name: string;
  path: string;
  method: HttpMethod;
  description?: string | null;
  tags?: string | null;
  disabled: boolean;
  policy?: ApiPolicy | null;
  activeVersions?: string;
  createdAt: string;
  updatedAt?: string | null;
  createdBy?: string | null;
}

export interface ApiDetail extends ApiSummary {
  versions: ApiVersion[];
  latestVersionId?: string;
  latestVersionNumber?: number;
  latestStatus?: VersionStatus;
  latestLabel?: string;
  draftVersionId?: string | null;
  publishedVersionId?: string | null;
}

export interface ValidationIssue {
  severity: 'ERROR' | 'WARNING';
  code: string;
  message: string;
  nodeId?: string | null;
}

export interface ValidationReport {
  valid: boolean;
  errors: number;
  warnings: number;
  issues: ValidationIssue[];
}

export interface CompileNodeProblem {
  severity: 'ERROR' | 'WARNING' | 'NOTE';
  line: number;
  rawLine?: number;
  column?: number;
  message: string;
  code?: string;
}

export interface CompileNodeResult {
  nodeId: string;
  success: boolean;
  className?: string | null;
  problems: CompileNodeProblem[];
}

export interface CompileReport {
  success: boolean;
  nodes: number;
  compiled: number;
  failedNodeIds: string[];
  results: CompileNodeResult[];
}

export interface TraceStep {
  nodeId: string;
  nodeType: NodeType;
  label: string;
  status: TraceStatus;
  port?: string | null;
  input?: unknown;
  output?: unknown;
  durationMs: number;
  error?: string | null;
  logs?: string[];
  attempt?: number;
}

export interface AssertionOutcome {
  type: string;
  target?: string | null;
  operator?: string | null;
  expected?: unknown;
  actual?: unknown;
  passed: boolean;
  message: string;
}

export interface SimulationResult {
  statusCode: number;
  statusText?: string;
  headers: Record<string, string>;
  body: unknown;
  trace: TraceStep[];
  durationMs: number;
  traceId: string;
  logs: string[];
  touchedTables: string[];
  assertions: AssertionOutcome[];
  passed: number;
  failed: number;
  environment?: string;
}

export interface TestCase {
  id: string;
  apiId: string;
  versionId?: string | null;
  name: string;
  description?: string | null;
  method: string;
  path?: string | null;
  headers?: unknown;
  query?: unknown;
  body?: unknown;
  mocks?: unknown;
  seed?: unknown;
  assertions?: unknown;
  csvData?: string | null;
  enabled: boolean;
  blocking: boolean;
  createdAt: string;
  createdBy?: string | null;
}

export interface TestRunSummary {
  id: string;
  apiId: string;
  versionId?: string | null;
  testCaseId?: string | null;
  testCaseName?: string | null;
  status: RunStatus;
  environment: string;
  statusCode: number;
  durationMs: number;
  passedAssertions: number;
  failedAssertions: number;
  traceId?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  assertions?: AssertionOutcome[];
  trace?: TraceStep[];
  response?: unknown;
}

export interface SuiteSummary {
  apiId: string;
  versionId: string;
  environment: string;
  total: number;
  passed: number;
  failed: number;
  errors: number;
  durationMs: number;
  success: boolean;
  results: TestRunSummary[];
  ranAt: string;
  ranBy: string;
}

export interface PreflightCheckItem {
  name: string;
  passed: boolean;
  status: 'OK' | 'BLOCKED';
  message: string;
  detail?: unknown;
}

export interface PreflightCheck {
  ready: boolean;
  versionId: string;
  versionLabel: string;
  apiId: string;
  apiName: string;
  environment: string;
  checks: PreflightCheckItem[];
}

export interface Approval {
  id: string;
  apiId: string;
  versionId: string;
  environment: string;
  status: ApprovalStatus;
  reason?: string | null;
  flowDiff?: unknown;
  javaDiff?: unknown;
  requestedBy?: string | null;
  createdAt: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  decisionNote?: string | null;
}

export interface ReleaseBundle {
  id: string;
  apiId: string;
  versionId: string;
  versionNumber: number;
  artifactHash?: string | null;
  createdAt: string;
  createdBy?: string | null;
  approvalId?: string | null;
  testSummary?: unknown;
  hasBytecode?: boolean;
  seedCount?: number;
}

export interface Deployment {
  id: string;
  apiId: string;
  versionId: string;
  releaseId?: string | null;
  environment: string;
  status: DeploymentStatus;
  strategy: DeploymentStrategy;
  canaryPercent: number;
  versionNumber: number;
  readiness?: unknown;
  message?: string | null;
  createdAt: string;
  createdBy?: string | null;
  activatedAt?: string | null;
  drainedAt?: string | null;
}

export interface EnvironmentInfo {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  requiresApproval: boolean;
  simulationEnabled: boolean;
  canaryEnabled: boolean;
  instances: { id: string; baseUrl?: string; zone?: string }[];
  variables: Record<string, string>;
}

export interface SecretInfo {
  id: string;
  name: string;
  environment: string;
  hint?: string | null;
  description?: string | null;
  vaultPath?: string | null;
  managedExternally?: boolean;
  createdAt: string;
  updatedAt?: string | null;
  updatedBy?: string | null;
}

export interface ConsumerInfo {
  id: string;
  name: string;
  description?: string | null;
  apiKeyHint?: string | null;
  apiKey?: string;
  allowedApiIds?: string | null;
  dailyQuota?: number;
  enabled?: boolean;
  createdAt?: string;
  createdBy?: string | null;
}

export interface AuditLogEntry {
  id: string;
  action: string;
  entityType: string;
  entityId?: string | null;
  summary?: string | null;
  actor?: string | null;
  actorRole?: string | null;
  createdAt: string;
  diff?: unknown;
}

export interface RequestLogEntry {
  id: string;
  apiId?: string | null;
  versionId?: string | null;
  environment: string;
  method: string;
  path: string;
  status: number;
  latencyMs: number;
  traceId?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  trace?: TraceStep[] | null;
}

export interface MetricsPoint {
  bucket: string;
  apiId?: string;
  environment?: string;
  requests: number;
  errors: number;
  avgLatencyMs: number;
  maxLatencyMs: number;
  errorRate: number;
}

export interface MetricsSeries {
  points: MetricsPoint[];
  summary: {
    totalRequests: number;
    totalErrors: number;
    avgLatencyMs: number;
    maxLatencyMs: number;
    errorRate: number;
    windowMinutes?: number;
  };
}

export interface AlertInfo {
  type: string;
  severity: string;
  apiId?: string;
  environment?: string;
  message: string;
}

export interface FunctionTemplateInfo {
  id: string;
  templateKey: string;
  name: string;
  category: string;
  description?: string | null;
  tags?: string | null;
  builtin?: boolean;
  latestVersion?: number | null;
  versionCount?: number;
  createdAt?: string;
  createdBy?: string | null;
}

export interface TemplateVersionInfo {
  id: string;
  templateId: string;
  templateKey: string;
  versionNumber: number;
  qualifiedKey: string;
  implType: 'COMPOSITE' | 'JAVA' | 'SCRIPT';
  implementation: string;
  inputs: { name: string; type: string; required: boolean; description?: string }[] | null;
  output: unknown;
  changeNote?: string | null;
  latest: boolean;
  createdAt?: string;
  createdBy?: string | null;
}

export interface FunctionTemplateDetail extends FunctionTemplateInfo {
  versions: TemplateVersionInfo[];
}

export interface DbSeedRow {
  table: string;
  rows: Record<string, unknown>[];
}

export interface HttpMockConfig {
  urlPattern: string;
  status?: number;
  headers?: Record<string, string>;
  body?: unknown;
  delayMs?: number;
  mode?: 'MOCK' | 'REAL' | 'ERROR_TIMEOUT' | 'ERROR_5XX';
}
