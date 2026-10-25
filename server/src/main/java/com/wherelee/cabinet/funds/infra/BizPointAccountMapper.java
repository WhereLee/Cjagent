package com.wherelee.cabinet.funds.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.funds.domain.BizPointAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizPointAccountMapper extends BaseMapper<BizPointAccount> {

    @Update("""
            update biz_point_account
               set points = points + #{delta}, version = version + 1, update_time = NOW(3)
             where id = #{id} and points + #{delta} >= 0 and deleted = 0
            """)
    int changeAvailable(@Param("id") Long id, @Param("delta") long delta);

    @Update("""
            update biz_point_account
               set frozen_points = frozen_points + #{delta}, version = version + 1, update_time = NOW(3)
             where id = #{id} and frozen_points + #{delta} >= 0 and deleted = 0
            """)
    int changeFrozen(@Param("id") Long id, @Param("delta") long delta);

    @Update("""
            update biz_point_account
               set deposit_points = deposit_points + #{delta}, version = version + 1, update_time = NOW(3)
             where id = #{id} and deposit_points + #{delta} >= 0 and deleted = 0
            """)
    int changeDeposit(@Param("id") Long id, @Param("delta") long delta);
}
