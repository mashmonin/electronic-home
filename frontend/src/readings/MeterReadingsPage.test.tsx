import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import * as api from './readingsApi'
import { validateReading } from './validation'

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ status: 'UP' })))
})
afterEach(() => vi.restoreAllMocks())

async function renderPage() {
  render(<MeterReadingsPage today={new Date(2026, 7, 10)} />)
  await screen.findByText('Ввод показаний')
}

describe('MeterReadingsPage', () => {
  it('shows header, period, risers and contacts', async () => {
    await renderPage()
    expect(screen.getByText('ТСЖ «Комфорт»')).toBeInTheDocument()
    expect(screen.getByText('Показания')).toBeInTheDocument()
    expect(screen.getByText('Заявки')).toBeInTheDocument()
    expect(screen.getByText('ул. Садовая, д. 12, кв. 47')).toBeInTheDocument()
    expect(screen.getByText('Лицевой счёт № 0004521873')).toBeInTheDocument()
    expect(screen.getByText(/Август 2026 · Передайте показания до 25 числа/)).toBeInTheDocument()
    expect(screen.getByText('Стояк 1')).toBeInTheDocument()
    expect(screen.getByText('Стояк 2')).toBeInTheDocument()
    expect(screen.getAllByPlaceholderText('0.000')).toHaveLength(4)
    expect(screen.getByText('Пред. показание: 245.300 м³')).toBeInTheDocument()
    expect(screen.getAllByText('Счётчик № —')).toHaveLength(2)
    expect(screen.getByText('Нужна помощь?')).toBeInTheDocument()
    expect(screen.getByText('Председатель правления ТСЖ')).toBeInTheDocument()
    expect(screen.getByText('+7 (999) 123-45-67')).toBeInTheDocument()
  })

  it('rejects a reading lower than the previous one', async () => {
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
  })

  it('switches the apartment from the header', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Сменить адрес' }))
    fireEvent.click(await screen.findByText('ул. Садовая, д. 12, кв. 48'))
    expect(await screen.findByText('Лицевой счёт № 0004521874')).toBeInTheDocument()
    expect(screen.getByText('Стояк 1')).toBeInTheDocument()
    expect(screen.queryByText('Стояк 2')).not.toBeInTheDocument()
  })

  it('adds a riser', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: '+ Добавить стояк' }))
    expect(screen.getByText('Стояк 3')).toBeInTheDocument()
  })

  it('does not submit an empty form', async () => {
    const submit = vi.spyOn(api, 'submitReadings')
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Введите хотя бы одно показание')).toBeInTheDocument()
    expect(submit).not.toHaveBeenCalled()
  })

  it('submits valid readings with a comma separator', async () => {
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue()
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '250,125' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalledWith({
      apartmentId: 'sadovaya-12-47',
      risers: [{ hot: '250.125', cold: undefined }, { hot: undefined, cold: undefined }],
    })
  })

  it('clears values and errors on reset', async () => {
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    await screen.findByText('Показание не может быть меньше предыдущего')
    fireEvent.click(screen.getByRole('button', { name: 'Сбросить форму' }))
    await waitFor(() => {
      expect(screen.queryByText('Показание не может быть меньше предыдущего')).not.toBeInTheDocument()
    })
    expect(screen.getAllByLabelText('Горячая вода, м³')[0]).toHaveValue('')
  })
})

describe('validateReading', () => {
  it('accepts empty and valid values', () => {
    expect(validateReading(undefined, 10)).toBeUndefined()
    expect(validateReading('', 10)).toBeUndefined()
    expect(validateReading('250.125', 245.3)).toBeUndefined()
    expect(validateReading('250,5', 245.3)).toBeUndefined()
    expect(validateReading('5', undefined)).toBeUndefined()
  })

  it('rejects non-numeric, negative and over-precise values', () => {
    expect(validateReading('abc', 0)).toBeDefined()
    expect(validateReading('-1', 0)).toBeDefined()
    expect(validateReading('1.2345', 0)).toBeDefined()
  })

  it('rejects values below the previous reading', () => {
    expect(validateReading('245.299', 245.3)).toBe('Показание не может быть меньше предыдущего')
  })
})
