package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizCabinetMapper extends BaseMapper<BizCabinet> {

    /** 连续失败达阈值：停用整台柜机（不再往坏柜机里放新单）。 */
    @Update("update biz_cabinet set cabinet_status = 'DISABLED', update_time = NOW(3) "
            + "where id = #{cabinetId} and deleted = 0")
    int disableCabinet(@Param("cabinetId") Long cabinetId);
}
