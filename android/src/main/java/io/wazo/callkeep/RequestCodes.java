package io.wazo.callkeep;

public enum RequestCodes {
    OPEN_MAIN_SCREEN(2136),
    OPEN_CALLING_SCREEN(2137),
    END_CALL(2138),
    ROUTE_SPEAKER(2140),
    ROUTE_EARPIECE(2141),
    CALL_BACK(2142),
    MISSED_CALL(2143),
    DENY_CALL(2144),
    ANSWER_CALL(2145);

    RequestCodes(int code) {
        requestCode = code;
    }

    public final int requestCode;
}
