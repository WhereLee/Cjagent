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
 *   <li><b>门没关一律拦</b>，远程也不例外——“人不在现场”不能成为绕过条件的理由，
 *       否则离开现场比留在现场更容易脱身；</li>
 *   <li><b>检测到有物一律拦</b>：东西还在柜里，这个格口就还在被他占用，计费就不该停。
 *       出口是“取走”或“声明放弃”（后者由调用方走 {@code ABANDONED}，并把格口转异常）；</li>
 *   <li><b>拿不到空柜凭据时（{@code UNKNOWN} 或结论过期）：现场不拦人、但格口必须锁住；远程直接拦</b>。
 *       物检是本设备形态里<b>必备的能力</b>（设备必须能回答“有没有东西”），所以这一类
 *       只在<b>传感器坏了</b>时发生，它是故障路径而不是常态路径。故障时不能拿它当放行凭据，
 *       也不该让用户的脚被一台坏传感器钉在柜机前：所以现场让他走，同时把格口标为待确认（由调用方写入），
 *       格子等一次人来确认；而远程提交者看不见现场，拿不到凭据就不许停表。</li>
 * </ul>
 *
 * <p>这里不需要也不应该出现“大多数柜机只有门磁”这种前提：那是把行业里另一种设备形态搬进来，
 * 然后又在解决自己造出来的问题（本项目的设备规则里它不存在）。
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
     * @param blocker        拦下原因；{@link Blocker#NONE} 表示可以结束
     * @param userMessage    给用户的说法。<b>必须说清缺哪一条以及下一步能做什么</b>，
     *                       只回"当前状态不可操作"在这种流程里就是逼用户打客服电话
     * @param evidenceBacked 结束是否拿到了"柜内已空"的凭据。<b>为 false 时调用方必须把格口
     *                       标为待确认清空</b>——人可以走，格子不能不设防地回到可售池
     */
    public record Verdict(Blocker blocker, String userMessage, boolean evidenceBacked) {

        public boolean closable() {
            return blocker == Blocker.NONE;
        }

        /** 拿到了凭据的结束。 */
        static Verdict closeWithEvidence() {
            return new Verdict(Blocker.NONE, null, true);
        }

        /** 凭据缺失但允许现场结束（调用方需锁格）。 */
        static Verdict closeWithoutEvidence(String message) {
            return new Verdict(Blocker.NONE, message, false);
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
                            : "柜门未关闭，无法远程结束订单；请回到柜机关好门，或稍后再试", false);
        }
        if (presence == Presence.PRESENT) {
            return new Verdict(Blocker.CONTENT_PRESENT,
                    "柜内检测到物品：请开门取出后再结束；若确认不要这件物品，可选择放弃并结束", false);
        }
        boolean noEvidence = presence == null || presence == Presence.UNKNOWN || !presenceFresh;
        if (noEvidence) {
            // 没有凭据就不能把“结不结束”和“格子能不能卖”绑在一起：现场让人走，但格口要被锁住
            String message = atSite
                    ? "设备无法确认柜内是否已清空（物检不可用），已允许你结束，但这个格口需要现场确认后才能重新使用"
                    : "当前无法确认柜内是否已清空，远程不能结束订单；可回到柜机确认，或声明放弃柜内物品";
            return atSite
                    ? Verdict.closeWithoutEvidence(message)
                    : new Verdict(Blocker.CONTENT_UNVERIFIED, message, false);
        }
        return Verdict.closeWithEvidence();
    }
}
