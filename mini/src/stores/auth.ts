/**
 * 登录态轻量存储（uni storage，跨端可用）。
 * 只存 token 对与登录设备标识——手机号等身份数据由后端脱敏下发，前端不得自行存储 openid 等身份数据。
 */
const TOKEN_KEY = 'cabinet-mini-token'
const REFRESH_KEY = 'cabinet-mini-refresh'
const DEVICE_KEY = 'cabinet-mini-device-code'
const PROFILE_KEY = 'cabinet-mini-profile'

export interface SavedProfile {
  tenantCode: string
  phone: string
}

export function getToken(): string {
  return String(uni.getStorageSync(TOKEN_KEY) ?? '')
}

export function getRefreshToken(): string {
  return String(uni.getStorageSync(REFRESH_KEY) ?? '')
}

export function setToken(accessToken: string, refreshToken: string): void {
  uni.setStorageSync(TOKEN_KEY, accessToken)
  uni.setStorageSync(REFRESH_KEY, refreshToken)
}

export function clearToken(): void {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(REFRESH_KEY)
}

export function isLoggedIn(): boolean {
  return Boolean(getToken())
}

/**
 * 本机登录标识（登录接口的 code）。
 *
 * 真机上这个值来自 wx.login；本项目没有微信 SDK，dev 下后端 mock 登录接受任意 code，
 * 所以由本机生成并持久化：同一个 code 就是同一个客户，换 code 即换账号。
 * 它只在“演示登录”里使用，页面不把它当业务字段展示。
 */
export function getDeviceCode(): string {
  let code = String(uni.getStorageSync(DEVICE_KEY) ?? '')
  if (!code) {
    code = `demo-${Math.random().toString(36).slice(2, 10)}`
    uni.setStorageSync(DEVICE_KEY, code)
  }
  return code
}

/** 换一个本机账号：清掉 code、登录态与上次资料，回到首次使用表单 */
export function switchAccount(): void {
  uni.removeStorageSync(DEVICE_KEY)
  uni.removeStorageSync(PROFILE_KEY)
  clearToken()
}

export function getSavedProfile(): SavedProfile {
  const raw = uni.getStorageSync(PROFILE_KEY)
  if (raw && typeof raw === 'object') {
    const value = raw as Partial<SavedProfile>
    return { tenantCode: value.tenantCode ?? '', phone: value.phone ?? '' }
  }
  return { tenantCode: '', phone: '' }
}

export function saveProfile(profile: SavedProfile): void {
  uni.setStorageSync(PROFILE_KEY, profile)
}
