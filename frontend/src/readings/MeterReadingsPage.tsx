import { Flex, Grid, Layout, Spin, Typography } from 'antd'
import { useEffect, useState } from 'react'
import AppHeader from './AppHeader'
import ContactCard from './ContactCard'
import ReadingsForm from './ReadingsForm'
import { getApartments } from './readingsApi'
import type { Apartment } from './types'

const MONTHS = [
  'Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь',
]
const DEADLINE_DAY = 25

interface Props {
  today?: Date
}

export default function MeterReadingsPage({ today = new Date() }: Props) {
  const [apartments, setApartments] = useState<Apartment[]>([])
  const [apartmentId, setApartmentId] = useState<string>()
  const wide = Grid.useBreakpoint().md ?? false

  useEffect(() => {
    getApartments().then((list) => {
      setApartments(list)
      setApartmentId(list[0]?.id)
    })
  }, [])

  const apartment = apartments.find((a) => a.id === apartmentId)
  if (!apartment) {
    return <Spin style={{ display: 'block', margin: '120px auto' }} />
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <AppHeader apartments={apartments} apartment={apartment} onApartmentChange={setApartmentId} />
      <Layout.Content style={{ padding: wide ? '38px 40px' : '24px 16px' }}>
        <Flex vertical gap={24} style={{ maxWidth: 700, margin: '0 auto' }}>
          <div style={{ textAlign: 'center' }}>
            <Typography.Title level={1} style={{ fontSize: 26, margin: 0 }}>
              Ввод показаний
            </Typography.Title>
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>
              {MONTHS[today.getMonth()]} {today.getFullYear()} · Передайте показания до {DEADLINE_DAY} числа
            </Typography.Text>
          </div>
          <ReadingsForm key={apartment.id} apartment={apartment} />
          <ContactCard />
        </Flex>
      </Layout.Content>
    </Layout>
  )
}
