package com.atina.jdeMCPServer.salesorder.model;

public record CustomerAddress(
        String addressLine1,
        String addressLine2,
        String addressLine3,
        String addressLine4,
        String city,
        String stateCode,
        String postalCode,
        String countryCode
) {
    public static CustomerAddress empty() {
        return new CustomerAddress("", "", "", "", "", "", "", "");
    }
}
