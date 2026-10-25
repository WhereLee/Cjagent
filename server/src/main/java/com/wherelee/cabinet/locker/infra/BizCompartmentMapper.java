package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.Presence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BizCompartmentMapper extends BaseMapper<BizCompartment> {

    @Update("update biz_compartment set status = 'RESERVED', current_order_id = #{orderId}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and status = 'FREE' and door_open_at is null and anomaly is null "
            + "and deleted = 0")
    int reserveSlot(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    @Update("update biz_compartment set status = 'FREE', current_order_id = null, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and current_order_id = #{orderId} and status = 'RESERVED' and deleted = 0")
    int releaseSlot(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    @Update("update biz_compartment set status = 'FREE', current_order_id = null, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and current_order_id = #{orderId} and status = 'OCCUPIED' and deleted = 0")
    int releaseOccupiedSlot(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    @Update("update biz_compartment set status = 'OCCUPIED', version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and current_order_id = #{orderId} and status = 'RESERVED' and deleted = 0")
    int markOccupied(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    @Update("""
            update biz_compartment
               set status = 'FAULT', current_order_id = null,
                   door_open_at = null, version = version + 1, update_time = NOW(3)
             where id = #{slotId} and deleted = 0 and status in ('RESERVED', 'OCCUPIED')
            """)
    int markFaulty(@Param("slotId") Long slotId);

    @Update("update biz_compartment set status = 'FAULT', update_time = NOW(3) "
            + "where id = #{slotId} and deleted = 0")
    int markSlotFault(@Param("slotId") Long slotId);

    @Update("update biz_compartment set door_open_at = #{at}, door_opened_by = #{customerId}, "
            + "door_closed_at = null, door_closed_by = null, version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and door_open_at is null and deleted = 0")
    int markDoorOpen(@Param("slotId") Long slotId, @Param("customerId") Long customerId,
                     @Param("at") LocalDateTime at);

    @Update("update biz_compartment set door_open_at = null, door_closed_at = #{at}, door_closed_by = #{customerId}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and door_open_at is not null and deleted = 0")
    int markDoorClosed(@Param("slotId") Long slotId, @Param("customerId") Long customerId,
                       @Param("at") LocalDateTime at);

    @Update("update biz_compartment set presence = #{presence}, presence_checked_at = #{at}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and deleted = 0")
    int recordPresence(@Param("slotId") Long slotId, @Param("presence") Presence presence,
                       @Param("at") LocalDateTime at);

    @Update("update biz_compartment set anomaly = #{anomaly}, anomaly_at = #{at}, anomaly_reason = #{reason}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and deleted = 0")
    int markAnomaly(@Param("slotId") Long slotId, @Param("anomaly") CompartmentAnomaly anomaly,
                    @Param("at") LocalDateTime at, @Param("reason") String reason);

    @Update("update biz_compartment set anomaly = null, anomaly_at = null, anomaly_reason = null, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and anomaly is not null and deleted = 0")
    int clearAnomaly(@Param("slotId") Long slotId);
}
