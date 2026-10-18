import type { ThemeConfig } from 'antd'

// Tokens taken from the Figma frame 02-meter-readings.
export const theme: ThemeConfig = {
  token: {
    colorPrimary: '#ffb370',
    colorBgLayout: '#fffbeb',
    colorText: '#262626',
    colorTextSecondary: '#595959',
    colorTextTertiary: '#8c8c8c',
    colorTextPlaceholder: '#bfbfbf',
    colorBorder: '#d9d9d9',
    colorBorderSecondary: '#f0f0f0',
    borderRadius: 8,
    fontSize: 14,
    controlHeightLG: 40,
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
      headerPadding: 0,
      headerHeight: 80,
    },
    Input: {
      inputFontSizeLG: 14,
    },
  },
}
