package com.wherelee.cabinet.identity.api;

public interface CustomerAdminPort {

    void changeCustomerStatus(Long customerId, Integer status, String reason);
}
