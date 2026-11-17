-- H14: the web panel signs in as a device of its own ("web"), so it shows in the device list and can be signed out.
alter table devices drop constraint devices_platform_check;
alter table devices add constraint devices_platform_check check (platform in ('android', 'linux', 'web'));
