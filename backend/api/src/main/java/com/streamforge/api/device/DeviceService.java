package com.streamforge.api.device;

import com.streamforge.api.error.ConflictException;
import com.streamforge.api.error.InvalidRequestException;
import com.streamforge.api.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeviceService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public DeviceResponse create(String deviceId) {
        requireValidId(deviceId);
        return deviceRepository.insertIfAbsent(deviceId)
                .orElseThrow(() -> new ConflictException("Device ID is already registered."));
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(String deviceId) {
        requireValidId(deviceId);
        return deviceRepository.find(deviceId)
                .orElseThrow(() -> new NotFoundException("Device was not found."));
    }

    @Transactional(readOnly = true)
    public DevicePage list(Integer limit, Integer offset) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        int resolvedOffset = offset == null ? 0 : offset;
        if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
            throw new InvalidRequestException(List.of(new InvalidRequestException.FieldError(
                    "limit", "limit must be between 1 and 100")));
        }
        if (resolvedOffset < 0) {
            throw new InvalidRequestException(List.of(new InvalidRequestException.FieldError(
                    "offset", "offset must be zero or greater")));
        }
        return new DevicePage(deviceRepository.list(resolvedLimit, resolvedOffset), resolvedLimit, resolvedOffset);
    }

    private static void requireValidId(String deviceId) {
        if (!DeviceIds.isValid(deviceId)) {
            throw new InvalidRequestException(List.of(new InvalidRequestException.FieldError(
                    "deviceId", "deviceId format is invalid")));
        }
    }
}
