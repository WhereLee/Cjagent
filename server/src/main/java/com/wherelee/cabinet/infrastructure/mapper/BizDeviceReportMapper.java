package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDeviceReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 上报流水读写（只 INSERT）。
 */
@Mapper
public interface BizDeviceReportMapper extends BaseMapper<BizDeviceReport> {

    /**
     * 该格口已记录到的最大设备序号——<b>乱序收敛的依据</b>。
     *
     * <p>设备重启后 seq 归零重放、或网络把旧事件晚送到，都会让 seq 回退。
     * 业务只接受"比已记录更大"的序号推进状态，旧序号仍然入库（保留现场）但不改变状态。
     */
    @Select("""
            select max(seq) from biz_device_report
            where cabinet_id = #{cabinetId} and slot_id = #{slotId}
            """)
    Long selectMaxSeq(@Param("cabinetId") Long cabinetId, @Param("slotId") Long slotId);

    /**
     * 取某条指令最近一次上报（<b>超时回扫的现场源</b>）。
     *
     * <p>为什么用前缀匹配而不是等值：重试轮次的 dedup_key 带 {@code #rN} 后缀（同一轮内的重复送达
     * 才算重复，跳轮是新的一次物理事件），等值查只能看到第一轮。
     *
     * <p><b>必须带 {@code #} 分隔的那一支</b>：requestId 形如 {@code SO…:OPEN:1}，它是 {@code SO…:OPEN:10}
     * 的字面前缀；只写 {@code like 'cmd:%requestId%'} 会把第 10 次尝试的流水当成第 1 次的读回来。
     * 前缀 LIKE 仍走 {@code uk_report_dedup}（只有前导 % 才会失效）。
     *
     * <p>列清单不手写：这张表没有 {@code version}，手写清单漏列的代价（第 11 刀实测）
     * 比 {@code select *} 多取几列大得多。
     */
    @Select("""
            select * from biz_device_report
             where dedup_key = concat('cmd:', #{requestId})
                or dedup_key like concat('cmd:', #{requestId}, '#%')
            order by id desc limit 1
            """)
    BizDeviceReport selectLatestForCommand(@Param("requestId") String requestId);
}
