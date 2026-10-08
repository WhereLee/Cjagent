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
}
