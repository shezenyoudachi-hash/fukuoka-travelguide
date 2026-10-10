package com.fukuokatabi.spot;

import jakarta.persistence.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "spot")
public class Spot {

    public enum Category { SIGHT, DINNER, NIGHT }

    public enum Slot { AM, ANY, PM }

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    private Category category;

    private String name;
    private String zone;
    private String area;
    private double latitude;
    private double longitude;

    @Enumerated(EnumType.STRING)
    private Slot slot;

    @Column(name = "duration_minutes")
    private int durationMinutes;

    @Column(name = "far_level")
    private int farLevel;

    @Column(name = "car_recommended")
    private boolean carRecommended;

    @Column(name = "transit_ok")
    private boolean transitOk;

    private boolean indoor;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(name = "closed_days")
    private String closedDays;

    @Column(name = "best_months")
    private String bestMonths;

    @Column(name = "season_note")
    private String seasonNote;

    private String note;

    @Column(name = "hours_verified")
    private boolean hoursVerified;

    @Column(name = "official_url")
    private String officialUrl;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "spot_tag", joinColumns = @JoinColumn(name = "spot_id"))
    @MapKeyColumn(name = "tag")
    @Column(name = "weight")
    private Map<String, Integer> tags = new HashMap<>();

    protected Spot() {
    }

    /** テスト用 */
    public Spot(String id, Category category, String name, String zone, String area,
                double latitude, double longitude, Slot slot, int durationMinutes, int farLevel,
                boolean carRecommended, boolean transitOk, boolean indoor, String note,
                Map<String, Integer> tags) {
        this.id = id;
        this.category = category;
        this.name = name;
        this.zone = zone;
        this.area = area;
        this.latitude = latitude;
        this.longitude = longitude;
        this.slot = slot;
        this.durationMinutes = durationMinutes;
        this.farLevel = farLevel;
        this.carRecommended = carRecommended;
        this.transitOk = transitOk;
        this.indoor = indoor;
        this.note = note;
        this.tags = new HashMap<>(tags);
    }

    public Set<DayOfWeek> closedDaysOfWeek() {
        if (closedDays == null || closedDays.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return Arrays.stream(closedDays.split(","))
                .map(String::trim)
                .map(Spot::toDayOfWeek)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    public Set<Integer> bestMonthSet() {
        if (bestMonths == null || bestMonths.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(bestMonths.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static DayOfWeek toDayOfWeek(String s) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().startsWith(s.toUpperCase(Locale.ROOT))) {
                return d;
            }
        }
        return null;
    }

    public int tag(String key) {
        return tags.getOrDefault(key, 0);
    }

    public boolean isCityCenter() {
        return farLevel == 0;
    }

    public String getId() { return id; }
    public Category getCategory() { return category; }
    public String getName() { return name; }
    public String getZone() { return zone; }
    public String getArea() { return area; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Slot getSlot() { return slot; }
    public int getDurationMinutes() { return durationMinutes; }
    public int getFarLevel() { return farLevel; }
    public boolean isCarRecommended() { return carRecommended; }
    public boolean isTransitOk() { return transitOk; }
    public boolean isIndoor() { return indoor; }
    public LocalTime getOpenTime() { return openTime; }
    public LocalTime getCloseTime() { return closeTime; }
    public String getSeasonNote() { return seasonNote; }
    public String getNote() { return note; }
    public boolean isHoursVerified() { return hoursVerified; }
    public String getOfficialUrl() { return officialUrl; }
    public Map<String, Integer> getTags() { return Collections.unmodifiableMap(tags); }
}
