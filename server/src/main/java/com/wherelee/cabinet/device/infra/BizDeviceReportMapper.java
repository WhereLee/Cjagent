package com.wherelee.cabinet.device.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.device.domain.BizDeviceReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BizDeviceReportMapper extends BaseMapper<BizDeviceReport> {

    @Select("""
            select max(seq) from biz_device_report
            where cabinet_id = #{cabinetId} and slot_id = #{slotId}
            """)
    Long selectMaxSeq(@Param("cabinetId") Long cabinetId, @Param("slotId") Long slotId);

    @Select("""
            select * from biz_device_report
             where dedup_key = concat('cmd:', #{requestId})
                or dedup_key like concat('cmd:', #{requestId}, '#%')
            order by id desc limit 1
            """)
    BizDeviceReport selectLatestForCommand(@Param("requestId") String requestId);
}
