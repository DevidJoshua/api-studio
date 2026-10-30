import { useMemo, useState } from 'react';
import type { FunctionTemplateInfo, NodeType } from '../../api/types';
import { nodeCategories } from './nodeCatalog';
import { SearchInput } from '../../ui';

/**
 * Palette node di sisi kiri designer.
 *
 * Node dapat diseret ke kanvas (HTML5 drag) atau diklik untuk ditambahkan di
 * tengah kanvas. Template dari katalog ditampilkan terpisah agar dapat dipakai
 * langsung sebagai node TEMPLATE_CALL.
 */
export function NodePalette({
  onAddNode,
  templates,
  onUseTemplate,
}: {
  onAddNode: (type: NodeType) => void;
  templates: FunctionTemplateInfo[];
  onUseTemplate: (templateKey: string, version: number) => void;
}) {
  const [search, setSearch] = useState('');
  const categories = useMemo(() => nodeCategories(), []);

  const term = search.trim().toLowerCase();
  const filteredCategories = useMemo(
    () =>
      categories
        .map((group) => ({
          ...group,
          nodes: group.nodes.filter(
            (node) =>
              !term ||
              node.label.toLowerCase().includes(term) ||
              node.category.toLowerCase().includes(term) ||
              node.description.toLowerCase().includes(term),
          ),
        }))
        .filter((group) => group.nodes.length > 0),
    [categories, term],
  );

  const filteredTemplates = useMemo(
    () =>
      templates.filter(
        (template) =>
          !term ||
          template.name.toLowerCase().includes(term) ||
          template.templateKey.toLowerCase().includes(term) ||
          template.category.toLowerCase().includes(term),
      ),
    [templates, term],
  );

  /** Handler drag untuk node (HTML5 DnD). */
  const handleDragStart = (event: React.DragEvent, type: NodeType) => {
    event.dataTransfer.setData('application/x-node-type', type);
    event.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <div className="flex h-full flex-col overflow-hidden border-r border-slate-800 bg-slate-900">
      <div className="border-b border-slate-800 p-3">
        <h2 className="text-[11px] font-bold uppercase tracking-wider text-slate-500">
          Node Palette
        </h2>
        <p className="mt-0.5 text-[10px] text-slate-500">
          Seret ke kanvas atau klik untuk menambahkan.
        </p>
        <div className="mt-2">
          <SearchInput value={search} onChange={setSearch} placeholder="Cari node..." ariaLabel="Cari node" />
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-2 custom-scrollbar">
        {filteredCategories.map((group) => (
          <div key={group.category} className="mb-3">
            <h3 className="mb-1 px-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
              {group.category}
            </h3>
            <div className="space-y-1">
              {group.nodes.map((node) => (
                <button
                  key={node.type}
                  type="button"
                  draggable
                  onDragStart={(event) => handleDragStart(event, node.type)}
                  onClick={() => onAddNode(node.type)}
                  title={node.description}
                  className="group flex w-full cursor-grab items-center gap-2 rounded-md border border-transparent bg-slate-800/50 px-2 py-1.5 text-left transition-colors hover:border-slate-700 hover:bg-slate-800 active:cursor-grabbing"
                >
                  <span className={`grid h-6 w-6 shrink-0 place-items-center rounded ${node.bg} ${node.color}`}>
                    <span className="h-2 w-2 rounded-sm bg-current" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[11px] font-medium text-slate-300">
                      {node.label}
                    </span>
                    <span className="block truncate text-[10px] text-slate-500">
                      {node.description}
                    </span>
                  </span>
                  <span className="shrink-0 text-xs text-slate-600 opacity-0 transition-opacity group-hover:opacity-100">
                    +
                  </span>
                </button>
              ))}
            </div>
          </div>
        ))}

        {filteredTemplates.length > 0 && (
          <div className="mb-3">
            <h3 className="mb-1 px-1 text-[10px] font-semibold uppercase tracking-wider text-cyan-500">
              Function Template
            </h3>
            <div className="space-y-1">
              {filteredTemplates.slice(0, 20).map((template) => (
                <button
                  key={template.id}
                  type="button"
                  onClick={() => onUseTemplate(template.templateKey, template.latestVersion ?? 1)}
                  title={template.description ?? template.templateKey}
                  className="flex w-full items-center gap-2 rounded-md border border-transparent bg-cyan-950/30 px-2 py-1.5 text-left transition-colors hover:border-cyan-800/60 hover:bg-cyan-950/50"
                >
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[11px] font-medium text-cyan-200">
                      {template.name}
                    </span>
                    <span className="block truncate font-mono text-[10px] text-cyan-600">
                      {template.templateKey}@{template.latestVersion ?? 1}
                    </span>
                  </span>
                </button>
              ))}
            </div>
          </div>
        )}

        {filteredCategories.length === 0 && filteredTemplates.length === 0 && (
          <p className="px-2 py-4 text-center text-[11px] text-slate-500">
            Tidak ada node yang cocok.
          </p>
        )}
      </div>
    </div>
  );
}
