import type { ComponentType } from 'react';
import type { FigureKind } from '../../lib/types';
import {
  AnalyzerPipeline,
  BoolTree,
  InvertedIndex,
  PagingWindow,
  RefreshTimeline,
  RelevanceStack,
  SegmentMerge,
  SuggestFunnel,
  WritePath,
} from './set-a';
import {
  AliasSwap,
  ConcurrencySeqNo,
  DynamicTemplateBranch,
  IngestPipeline,
  ObjectFlattenedNested,
  ReindexPipeline,
  TemplateResolution,
} from './set-b';

const FIGURES: Record<FigureKind, ComponentType> = {
  'write-path': WritePath,
  'refresh-timeline': RefreshTimeline,
  'segment-merge': SegmentMerge,
  'inverted-index': InvertedIndex,
  'analyzer-pipeline': AnalyzerPipeline,
  'bool-tree': BoolTree,
  'paging-window': PagingWindow,
  'alias-swap': AliasSwap,
  'reindex-pipeline': ReindexPipeline,
  'template-resolution': TemplateResolution,
  'dynamic-template-branch': DynamicTemplateBranch,
  'object-flattened-nested': ObjectFlattenedNested,
  'ingest-pipeline': IngestPipeline,
  'concurrency-seqno': ConcurrencySeqNo,
  'relevance-stack': RelevanceStack,
  'suggest-funnel': SuggestFunnel,
};

export default function Figure({ kind }: { kind: FigureKind }) {
  const Component = FIGURES[kind];
  return Component ? <Component /> : null;
}
