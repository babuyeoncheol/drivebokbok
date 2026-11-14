package kr.ansimgwiga.car.sim

import kr.ansimgwiga.car.core.DemoScript
import kr.ansimgwiga.car.core.DoorPos
import kr.ansimgwiga.car.core.Maintenance
import kr.ansimgwiga.car.core.SeatPos
import kr.ansimgwiga.car.core.TripSignal

/** 시뮬레이터 화면의 스위치 상태. 버튼을 누를 때마다 신호 하나를 만들고 이 상태도 바꾼다. */
data class SimState(
    val gearParked: Boolean = true,
    val speedKmh: Float = 0f,
    val doors: Map<DoorPos, Boolean> = DoorPos.entries.associateWith { false },
    val driverBeltWarning: Boolean = false,
    val rearBuckled: Boolean? = null,
    val airbag: Boolean = false,
    val maintenance: Set<Maintenance> = emptySet(),
    val energyLow: Boolean = false,
    val brake: Boolean = false,
    val headlights: Boolean = false,
    val wiper: Boolean = false,
    val destination: String? = null,
    val pathT: Double = 0.0
) {
    fun doorOpen(pos: DoorPos) = doors[pos] == true

    /** 신호 하나를 반영한 새 상태. */
    fun apply(s: TripSignal): SimState = when (s) {
        is TripSignal.Gear -> copy(gearParked = s.parked)
        is TripSignal.Speed -> copy(speedKmh = s.kmh)
        is TripSignal.Door -> copy(doors = doors + (s.pos to s.open))
        is TripSignal.BeltWarning -> if (s.seat == SeatPos.DRIVER) copy(driverBeltWarning = s.active) else this
        is TripSignal.SeatBuckled -> if (s.seat.isRear) copy(rearBuckled = s.buckled) else this
        is TripSignal.Airbag -> copy(airbag = s.deployed)
        is TripSignal.MaintenanceWarning -> copy(maintenance = if (s.active) maintenance + s.kind else maintenance - s.kind)
        is TripSignal.EnergyLow -> copy(energyLow = s.low)
        is TripSignal.Brake -> copy(brake = s.pressed)
        is TripSignal.Lights -> copy(headlights = s.headlights ?: headlights)
        is TripSignal.Wiper -> copy(wiper = s.on)
        is TripSignal.Destination -> copy(destination = s.name)
        is TripSignal.Location, is TripSignal.UxRestricted, is TripSignal.Odometer, TripSignal.NaviArrived,
        is TripSignal.SeatOccupied -> this
    }

    /** 이 상태를 엔진에 처음 알려 줄 신호들(엔진을 새로 만들 때). */
    fun baseline(): List<TripSignal> = buildList {
        add(TripSignal.Gear(gearParked))
        add(TripSignal.Speed(speedKmh))
        add(TripSignal.Airbag(airbag))
        add(TripSignal.Brake(brake))
        add(TripSignal.EnergyLow(energyLow))
        add(TripSignal.Lights(headlights = headlights, fog = false))
        add(TripSignal.Wiper(wiper))
        add(TripSignal.BeltWarning(SeatPos.DRIVER, driverBeltWarning))
        rearBuckled?.let { add(TripSignal.SeatBuckled(SeatPos.REAR_LEFT, it)) }
        Maintenance.entries.forEach { add(TripSignal.MaintenanceWarning(it, it in maintenance)) }
        DoorPos.entries.forEach { add(TripSignal.Door(it, doorOpen(it))) }
        add(TripSignal.Destination(destination))
        val p = DemoScript.pointAt(pathT)
        add(TripSignal.Location(p.lat, p.lng))
    }
}
