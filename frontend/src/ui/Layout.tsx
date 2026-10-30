import type { ReactNode } from 'react';

/** Grid responsif untuk kartu. */
export function Grid({
  children,
  cols = 3,
  gap = 3,
}: {
  children: ReactNode;
  cols?: 1 | 2 | 3 | 4;
  gap?: 2 | 3 | 4;
}) {
  const colClass =
    cols === 1
      ? 'grid-cols-1'
      : cols === 2
        ? 'grid-cols-1 md:grid-cols-2'
        : cols === 3
          ? 'grid-cols-1 md:grid-cols-2 xl:grid-cols-3'
          : 'grid-cols-1 md:grid-cols-2 xl:grid-cols-4';
  const gapClass = gap === 2 ? 'gap-2' : gap === 4 ? 'gap-4' : 'gap-3';
  return <div className={`grid ${colClass} ${gapClass}`}>{children}</div>;
}

/** Tumpukan vertikal dengan jarak konsisten. */
export function Stack({ children, gap = 3 }: { children: ReactNode; gap?: 1 | 2 | 3 | 4 }) {
  const gapClass =
    gap === 1 ? 'space-y-1' : gap === 2 ? 'space-y-2' : gap === 4 ? 'space-y-4' : 'space-y-3';
  return <div className={gapClass}>{children}</div>;
}
