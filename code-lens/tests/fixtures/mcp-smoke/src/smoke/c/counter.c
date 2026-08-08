#include "../../../include/smoke/counter.h"

static int clamp_positive(int value) {
    return value < 0 ? 0 : value;
}

int counter_add(Counter *counter, int amount) {
    counter->value += clamp_positive(amount);
    return counter->value;
}
