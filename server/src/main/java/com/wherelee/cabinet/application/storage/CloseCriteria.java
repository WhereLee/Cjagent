package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.domain.enums.Presence;

/**
 * “能不能结束订单、停止计费”的<b>纯判据</b>。
 *
 * <p>单独拿出来写成无副作用的函数，理由有两个：
 * ① 这是整套规则里唯一直接决定收不收钱的地方，组合必须被<b>穷举</b>而不是被"顺手测几个"覆盖
 * （单测里把 门×物检×时效×现场/远程 全跑一遍只要几十行，接到 DB 上做同样的事要几十条用例）；
 * ② 判据只有一份，现场结束、远程结束、巡检任务、后台强制结单都必须问同一个对象，
 * 否则就会出现"现场能结、远程不能结"这种没人能解释的差异。
 *
 * <p>三条规则的取舍（docs/门态与物品争议设计.md §3）：
 * <ul>
 *   <li><b>门没关一律拦</b>，远程也不例外——"人不在现场"不能成为绕过条件的理由，
 *       否则离开现场比留在现场更容易脱身；</li>
 *   <li><b>检测到有物一律拦</b>：东西还在柜里，这个格口就还在被他占用，计费就不该停。
 *       出口是"取走"或"声明放弃"（后者由调用方走 {@code ABANDONED}，并把格口转异常）；</li>
 *   <li><b>测不到柜内时，现场采信用户、远程不采信</b>。这一条是本类里唯一的不对称，理由值得写下：
 *       现实里大多数柜机只有门磁、没有物检，如果"测不到"就拦，主流程会全线走不通；
 *       而现场的人有手有眼、东西是他的，他当面声明结束的成本与责任都在他自己身上。
 *       远程的人看不见任何东西，凭一句话就要停掉一个"可能有物品"的格口的计费，
 *       留下的正是最难处理的"钱走了、东西还在柜里"。</li>
 * </ul>
 */
public final class CloseCriteria {

    /** 拦下结束的原因。{@link #NONE} 才是唯一可以停止计费的值。 */
    public enum Blocker {
        /** 条件齐了，可以结束 */
        NONE,
        /** 门还开着 */
        DOOR_NOT_CLOSED,
        /** 门关了但柜内检测到物品 */
        CONTENT_PRESENT,
        /** 门关了但柜内情况测不到（无凭据），远程不得结束 */
        CONTENT_UNVERIFIED
    }

    /**
     * @param blocker     拦下原因；{@link Blocker#NONE} 表示可以结束
     * @param userMessage 给用户的说法。<b>必须说清缺哪一条以及下一步能做什么</b>，
     *                    只回"当前状态不可操作"在这种流程里就是逼用户打客服电话
     */
    public record Verdict(Blocker blocker, String userMessage) {
        public boolean closable() {
            return blocker == Blocker.NONE;
        }
    }

    private CloseCriteria() {
    }

    /**
     * @param doorClosed     门磁结论（来自设备探测，不是"用户说他关了"）
     * @param presence       最近一次柜内物检结论，可为 null（从没测过）
     * @param presenceFresh  这条结论还在时效内（超龄等于没测过）
     * @param atSite         true=用户当面操作；false=远程提交
     */
    public static Verdict evaluate(boolean doorClosed, Presence presence, boolean presenceFresh, boolean atSite) {
        if (!doorClosed) {
            return new Verdict(Blocker.DOOR_NOT_CLOSED,
                    atSite ? "柜门还没关上，请先关好门再结束订单"
                            : "柜门未关闭，无法远程结束订单；请回到柜机关好门，或稍后再试");
        }
        if (presence == Presence.PRESENT) {
            return new Verdict(Blocker.CONTENT_PRESENT,
                    "柜内检测到物品：请开门取出后再结束；若确认不要这件物品，可选择放弃并结束");
        }
        boolean noEvidence = presence == null || presence == Presence.UNKNOWN || !presenceFresh;
        if (noEvidence && !atSite) {
            return new Verdict(Blocker.CONTENT_UNVERIFIED,
                    "当前无法确认柜内是否已清空，远程不能结束订单；可选择放弃柜内物品后结束");
        }
        // 现场 + 测不到：采信当面声明（大多数柜机只有门磁，拦下来就等于全线不能结单）
        return new Verdict(Blocker.NONE, null);
    }
}
