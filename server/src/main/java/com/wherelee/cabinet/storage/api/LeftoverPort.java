package com.wherelee.cabinet.storage.api;

public interface LeftoverPort {

    String openForRetrieval(Long customerId, String orderNo);

    String confirmRetrieved(Long customerId, String orderNo);
}
