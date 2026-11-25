// gnss_cli.cpp — 直接调 android.hardware.gnss@2.0::IGnss，不经过 LocationManager/App
// 目标: rolex / MSM8937 / Android 10 / arm64-v8a
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <atomic>

#include <android/hardware/gnss/2.0/IGnss.h>
#include <android/hardware/gnss/2.0/IGnssCallback.h>
#include <hidl/HidlTransportSupport.h>
#include <log/log.h>

using namespace android::hardware::gnss;
using android::hardware::hidl_string;
using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::Void;
using android::sp;

static std::atomic<int> gFixes{0};
static std::atomic<int> gEngineStarted{0};
static int gNmea = 0;
static int gVerbose = 0;

class GnssCb : public V2_0::IGnssCallback {
  public:
    // ---- 2.0 新增 ----
    Return<void> gnssLocationCb_2_0(const V2_0::GnssLocation& loc2) override {
        const V1_0::GnssLocation& l = loc2.v1_0;
        printf("FIX lat=%.7f lon=%.7f alt=%.1f hacc=%.1f vacc=%.1f "
               "spd=%.2f brg=%.1f ts=%lld\n",
               l.latitudeDegrees, l.longitudeDegrees, l.altitudeMeters,
               l.horizontalAccuracyMeters, l.verticalAccuracyMeters,
               l.speedMetersPerSec, l.bearingDegrees, (long long)l.timestamp);
        fflush(stdout);
        gFixes++;
        return Void();
    }

    Return<void> gnssSvStatusCb_2_0(
            const hidl_vec<V1_0::IGnssCallback::GnssSvInfo>& list) override {
        int used = 0;
        for (size_t i = 0; i < list.size(); i++) if (list[i].svFlag & 0x1) used++;
        if (gVerbose) {
            printf("SV visible=%zu used=%d\n", list.size(), used);
            fflush(stdout);
        }
        return Void();
    }

    Return<void> gnssSetCapabilitiesCb_2_0(uint32_t caps) override {
        if (gVerbose) { printf("CAPS2 0x%x\n", caps); fflush(stdout); }
        return Void();
    }

    Return<void> gnssRequestLocationCb_2_0(bool independentFromGnss,
                                          bool isUserEmergency) override {
        if (gVerbose) {
            printf("REQ_LOCATION indep=%d emergency=%d\n",
                   (int)independentFromGnss, (int)isUserEmergency);
            fflush(stdout);
        }
        return Void();
    }

    // ---- 1.x 继承 ----
    Return<void> gnssLocationCb(const V1_0::GnssLocation& l) override {
        return gnssLocationCb_2_0(V2_0::GnssLocation{l, {}});
    }

    Return<void> gnssStatusCb(V1_0::GnssStatusValue st) override {
        if (st != V1_0::GnssStatusValue::NONE) gEngineStarted++;
        if (gVerbose) { printf("ENGINE %d\n", (int)st); fflush(stdout); }
        return Void();
    }

    Return<void> gnssSvStatusCb(
            const V1_0::IGnssCallback::GnssSvStatus& sv) override {
        if (gVerbose) { printf("SV(1.x) num=%u\n", sv.numSvs); fflush(stdout); }
        return Void();
    }

    Return<void> gnssNmeaCb(int64_t ts, const hidl_string& nmea) override {
        if (gNmea) { printf("%s", nmea.c_str()); fflush(stdout); }
        return Void();
    }

    Return<void> gnssSetCapabilitesCb(uint32_t caps) override {
        if (gVerbose) { printf("CAPS 0x%x\n", caps); fflush(stdout); }
        return Void();
    }

    Return<void> gnssAcquireWakelockCb() override { return Void(); }
    Return<void> gnssReleaseWakelockCb() override { return Void(); }
    Return<void> gnssRequestTimeCb() override { return Void(); }

    Return<void> gnssSetSystemInfoCb(
            const V1_0::IGnssCallback::GnssSystemInfo& info) override {
        if (gVerbose) { printf("HW_YEAR %u\n", info.yearOfHw); fflush(stdout); }
        return Void();
    }

    Return<void> gnssNameCb(const hidl_string& name) override {
        if (gVerbose) { printf("HAL_NAME %s\n", name.c_str()); fflush(stdout); }
        return Void();
    }

    Return<void> gnssRequestLocationCb(bool independentFromGnss) override {
        return gnssRequestLocationCb_2_0(independentFromGnss, false);
    }
};

int main(int argc, char** argv) {
    long timeoutMs = 90000;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-n")) gNmea = 1;
        else if (!strcmp(argv[i], "-v")) gVerbose = 1;
        else if (!strcmp(argv[i], "-t") && i + 1 < argc) timeoutMs = atol(argv[++i]);
    }

    sp<V2_0::IGnss> gnss = V2_0::IGnss::getService();
    if (gnss == nullptr) {
        fprintf(stderr, "getService(android.hardware.gnss@2.0::IGnss/default) = null\n");
        return 2;
    }

    android::hardware::configureRpcThreadpool(1, false);

    sp<GnssCb> cb = new GnssCb();
    auto rcb = gnss->setCallback_2_0(cb);
    if (!rcb.isOk() || !rcb) {
        fprintf(stderr, "setCallback_2_0 failed (ok=%d ret=%d)\n",
                (int)rcb.isOk(), (int)rcb.withDefault((bool)false));
        return 3;
    }
    printf("callback registered\n"); fflush(stdout);

    auto rstart = gnss->start();
    printf("start ok=%d ret=%d\n", (int)rstart.isOk(),
           (int)rstart.withDefault((bool)false));
    fflush(stdout);

    long waited = 0;
    while (gFixes.load() == 0 && waited < timeoutMs) {
        usleep(100000);
        waited += 100;
    }

    if (gFixes.load() > 0) printf("DONE fixes=%d in %ldms\n", gFixes.load(), waited);
    else printf("NO FIX in %ldms (engineStarted=%d)\n", waited, gEngineStarted.load());

    gnss->stop();
    return gFixes.load() > 0 ? 0 : 4;
}
