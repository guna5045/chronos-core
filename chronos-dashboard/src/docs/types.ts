export type DocumentationCategoryId =
  | 'getting-started'
  | 'tasks'
  | 'workers'
  | 'coordination'
  | 'workflows'
  | 'data-reliability'
  | 'tenant-security'
  | 'observability'
  | 'ai-subsystem'
  | 'operations';

export interface DocumentationCategory {
  id: DocumentationCategoryId;
  title: string;
  description: string;
  iconName: string;
}

export type VisualizerId =
  | 'task-lifecycle'
  | 'leader-election'
  | 'worker-heartbeat'
  | 'consistent-hashing'
  | 'dag-execution'
  | 'dlq-lifecycle'
  | 'dispatch'
  | 'ai-workflow'
  | 'ai-operations'
  | 'observability-trace';

export interface DocumentationConcept {
  id: string;
  title: string;
  category: DocumentationCategoryId;
  summary: string;
  definition: string;
  purpose: string;
  chronosUsage: string;
  operationalSignificance: string;
  aliases: string[];
  relatedConceptIds: string[];
  visualizerId?: VisualizerId;
}

export type ControlPlanePageId =
  | 'overview'
  | 'tasks'
  | 'workers'
  | 'workflows'
  | 'dlq'
  | 'cluster'
  | 'tenants'
  | 'observability'
  | 'ai-workflows'
  | 'ai-operations';

export interface PageDocumentationMapping {
  pageId: ControlPlanePageId;
  pageTitle: string;
  subtitle: string;
  primaryConceptIds: string[];
}
