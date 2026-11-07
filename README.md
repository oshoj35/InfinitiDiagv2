# InfinitiDiag Option A — vLinker MC+ CAN starter

This project retains the Android GitHub Actions build setup and adds:
- Bluetooth Classic ELM connection
- ELM initialization for 11-bit 500 kbps CAN (`ATSP6`)
- Console tab
- Candidate CAN header probe tab
- Custom 11-bit CAN header and raw command sending
- Diagnostic log

## Safety and coverage
This is an exploratory raw-ELM/CAN build. Candidate `7E0`-`7E7` headers are not asserted as actual module addresses for the 2011 FX35. The probe uses standard OBD Mode 01 PID support (`0100`) only. It does not implement Nissan CONSULT-III / DDL3, OEM module-specific DTC reading, ISO-TP/UDS service mapping, module data definitions, or any active tests/coding. A non-response is not proof that a module is absent.

No write services, ECU coding, configuration, active tests, or DTC erase commands are included. Test only with ignition on and vehicle safely parked.
