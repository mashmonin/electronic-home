import { Button, Flex } from 'antd'

interface Props {
  page: number
  pages: number
  onChange: (page: number) => void
}

// The numbers shown: up to three pages around the current one, the first window is "1 2 3".
export function visiblePages(page: number, pages: number): number[] {
  const count = Math.min(3, pages)
  const first = Math.min(Math.max(page - 1, 1), pages - count + 1)
  return Array.from({ length: count }, (_, index) => first + index)
}

// "1", "1 2", "1 2 3" or "1 2 3 …": a click on a number opens the page, a click on "…" opens the page beyond (or before) the shown ones.
export default function RequestPages({ page, pages, onChange }: Props) {
  const shown = visiblePages(page, Math.max(pages, 1))
  const before = shown[0] > 1
  const after = shown[shown.length - 1] < pages
  return (
    <Flex gap={4} align="center" role="navigation" aria-label="Страницы заявок">
      {before && (
        <Button type="text" size="small" aria-label="Предыдущие страницы" onClick={() => onChange(shown[0] - 1)}>
          …
        </Button>
      )}
      {shown.map((number) => (
        <Button
          key={number}
          size="small"
          type={number === page ? 'primary' : 'text'}
          aria-current={number === page ? 'page' : undefined}
          onClick={() => onChange(number)}
        >
          {number}
        </Button>
      ))}
      {after && (
        <Button type="text" size="small" aria-label="Следующие страницы" onClick={() => onChange(shown[shown.length - 1] + 1)}>
          …
        </Button>
      )}
    </Flex>
  )
}
