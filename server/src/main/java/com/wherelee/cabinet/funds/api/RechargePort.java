package com.wherelee.cabinet.funds.api;

public interface RechargePort {

    RechargeView recharge(Long customerId, long points);
}
