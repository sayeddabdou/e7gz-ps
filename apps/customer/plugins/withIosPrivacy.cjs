const { withInfoPlist } = require("expo/config-plugins");

/** E7-6: the app requests location only while in use and does not use Motion, Face ID, or arbitrary HTTP loads. */
module.exports = function withIosPrivacy(config) {
  cleanInfoPlist(config.ios?.infoPlist);
  return withInfoPlist(config, (mod) => {
    cleanInfoPlist(mod.modResults);
    return mod;
  });
};

function cleanInfoPlist(info) {
  if (!info) return;
  delete info.NSLocationAlwaysAndWhenInUseUsageDescription;
  delete info.NSLocationAlwaysUsageDescription;
  delete info.NSMotionUsageDescription;
  delete info.NSFaceIDUsageDescription;

  if (info.NSAppTransportSecurity && typeof info.NSAppTransportSecurity === "object") {
    delete info.NSAppTransportSecurity.NSAllowsArbitraryLoads;
  }
}
