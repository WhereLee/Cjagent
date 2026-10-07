package com.wherelee.cabinet.infrastructure.wechat;

/**
 * 小程序登录侧的外部依赖：把 wx.login 拿到的 code 换成 openid。
 *
 * <p>抽成接口有两个目的：① 让"调微信"这个外部依赖可以被关闭或替换（没有 AppID 的本地开发、
 * CI、单元测试都靠它）；② 让登录业务不掺 HTTP 细节，权限/租户逻辑单独可测。
 */
public interface MiniAppClient {

    /**
     * @param code wx.login 返回的临时凭证（一次性，5 分钟内有效）
     * @return openid
     * @throws com.wherelee.cabinet.common.exception.BizException code 无效/已被使用/微信侧异常
     */
    String resolveOpenId(String code);
}
