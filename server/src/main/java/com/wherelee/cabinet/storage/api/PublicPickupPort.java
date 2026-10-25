package com.wherelee.cabinet.storage.api;

public interface PublicPickupPort {

    StorageOrderView pickup(String voucherCode, String phoneTail);
}
