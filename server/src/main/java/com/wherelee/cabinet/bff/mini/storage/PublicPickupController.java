package com.wherelee.cabinet.bff.mini.storage;

import com.wherelee.cabinet.storage.api.PublicPickupPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "公开-凭码取件", description = "柜机屏幕或无登录态时的第二条取件路径")
@RestController
@RequestMapping("/api/public/storage")
public class PublicPickupController {

    private final PublicPickupPort pickupService;

    public PublicPickupController(PublicPickupPort pickupService) {
        this.pickupService = pickupService;
    }

    public record PickupRequest(
            @NotBlank(message = "请填写取件码")
            @Size(max = 16, message = "取件码长度不正确")
            String voucherCode,
            @NotBlank(message = "请填写手机号后四位")
            @Pattern(regexp = "\\d{4}", message = "手机尾号是四位数字")
            String phoneTail) {
    }

    @Operation(summary = "凭取件码与手机尾号结束并取物",
            description = "定-25：两个都要对才能取；不区分是哪一个不对（防枚举探测）；"
                    + "同一单连续错 5 次锁定该单开柜，锁定后只能走客服或后台强制开柜")
    @RateLimit(limit = 5, windowSeconds = 60,
            dimension = RateLimit.Dimension.IP,
            message = "尝试次数过多，请一分钟后重试，或联系现场工作人员")
    @PostMapping("/pickup")
    public R<StorageOrderView> pickup(@Valid @RequestBody PickupRequest request) {
        return R.ok(pickupService.pickup(request.voucherCode(), request.phoneTail()));
    }
}
