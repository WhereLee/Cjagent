package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizCabinetMapper extends BaseMapper<BizCabinet> {

    @Update("update biz_cabinet set cabinet_status = 'DISABLED', update_time = NOW(3) "
            + "where id = #{cabinetId} and deleted = 0")
    int disableCabinet(@Param("cabinetId") Long cabinetId);
}
