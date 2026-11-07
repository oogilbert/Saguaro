package oog.mega.saguaro.info.learning.rule;

import oog.mega.saguaro.info.learning.rule.WaveReachability.Position;
import oog.mega.saguaro.info.learning.rule.WaveReachability.RadialRange;
import robocode.Rules;
import robocode.util.Utils;

/** Intersection of speed reachability and a generic analytic initial-motion bound. */
public final class InertialReachability {
    // |dv_vector|^2 <= DECELERATION^2 + v*(v+ACCELERATION)*turnRate(v)^2.
    // Bound each speed*turn factor at its quadratic maximum. This respects
    // the coupling between speed and turn rate, rather than taking both maxima.
    public static final double ACCELERATION_BOUND=Math.sqrt(Rules.DECELERATION*Rules.DECELERATION
            +maximumSpeedTurnProduct(0)*maximumSpeedTurnProduct(Rules.ACCELERATION));
    private static double maximumSpeedTurnProduct(double offset) {
        double maximum=Rules.getTurnRateRadians(0),slope=maximum-Rules.getTurnRateRadians(1);
        double v=Math.max(0,Math.min(Rules.MAX_VELOCITY,(maximum-offset*slope)/(2*slope)));
        return (v+offset)*Rules.getTurnRateRadians(v);
    }
    private static double uncertainty(double velocity,int k) {
        double accelerating=Math.min(k,Math.max(0,Math.floor((8-velocity)/Rules.ACCELERATION)));
        double travel=accelerating*velocity+Rules.ACCELERATION*accelerating*(accelerating+1)*.5
                +8*(k-accelerating);
        // Both are valid disks centered at P+v*K. The second follows directly
        // from the maximum traveled distance and the triangle inequality.
        return Math.min(ACCELERATION_BOUND*k*(k+1)*.5,travel+velocity*k);
    }
    public final double bearing,minOffset,maxOffset;
    private final double sx,sy,speed,fireTime;
    private final Position anchor;
    private final WaveReachability outer,constraint;

    /** Initial K ticks lie in disk P+v*K, radius A*K*(K+1)/2, without predicting controls.
     * Subsequent motion expands that disk at speed 8. K is capped at six and is
     * shortened so a wave or collision cannot occur during the bounded interval.
     * The resulting expanding disk is another analytic WaveReachability region. */
    public static WaveReachability initialConstraint(double sx,double sy,double speed,double fireTime,
            Position observed,double vx,double vy,double selfX,double selfY,double width,double height) {
        double gap=oog.mega.saguaro.math.MathUtils.hypot(observed.x()-sx,observed.y()-sy)-speed*(observed.time()-fireTime);
        double wall=Math.min(Math.min(observed.x()-18,width-18-observed.x()),
                Math.min(observed.y()-18,height-18-observed.y()));
        double separation=oog.mega.saguaro.math.MathUtils.hypot(observed.x()-selfX,observed.y()-selfY)-36*Math.sqrt(2);
        int cap=(int)Math.floor(Math.max(0,Math.min(6,Math.min(gap/(speed+8),Math.min(wall/8,separation/16)))));
        if(cap==0) return null;
        double velocity=oog.mega.saguaro.math.MathUtils.hypot(vx,vy);
        int k=Math.min(cap,Math.max(1,(int)Math.round((8+velocity)/ACCELERATION_BOUND-.5)));
        double uncertainty=uncertainty(velocity,k),full=uncertainty(velocity,cap);
        if((8+velocity)*cap-full>(8+velocity)*k-uncertainty) { k=cap;uncertainty=full; }
        Position center=new Position(observed.x()+vx*k,observed.y()+vy*k,
                observed.time()+k-uncertainty/8);
        return new WaveReachability(sx,sy,speed,fireTime,center,width,height);
    }

    public InertialReachability(double sx,double sy,double speed,double fireTime,Position anchor,
            double width,double height,WaveReachability constraint) {
        this.sx=sx;this.sy=sy;this.speed=speed;this.fireTime=fireTime;this.anchor=anchor;
        this.outer=new WaveReachability(sx,sy,speed,fireTime,anchor,width,height);
        this.constraint=constraint;bearing=outer.bearing;
        if(constraint==null) { minOffset=outer.minOffset;maxOffset=outer.maxOffset;return; }
        double shift=constraint==null ? 0 : Utils.normalRelativeAngle(constraint.bearing-bearing);
        double lower=constraint==null ? outer.minOffset : Math.max(outer.minOffset,shift+constraint.minOffset);
        double upper=constraint==null ? outer.maxOffset : Math.min(outer.maxOffset,shift+constraint.maxOffset);
        if(lower>upper) { minOffset=Double.NaN;maxOffset=Double.NaN;return; }
        double witness=Double.NaN;
        for(double candidate:new double[]{Math.max(lower,Math.min(upper,0)),
                Math.max(lower,Math.min(upper,shift)),(lower+upper)*.5,lower,upper}) {
            if(radialGap(bearing+candidate)<=1e-8) { witness=candidate;break; }
        }
        if(!Double.isFinite(witness)) {
            // Rare disjoint radial intervals: minimize their scalar separation.
            // This is geometry, never a movement integration or control search.
            double a=lower,b=upper;
            for(int i=0;i<40;i++) {
                double l=(2*a+b)/3,r=(a+2*b)/3;
                if(radialGap(bearing+l)<radialGap(bearing+r)) b=r;else a=l;
            }
            double candidate=(a+b)*.5;
            if(radialGap(bearing+candidate)<=1e-8) witness=candidate;
        }
        if(!Double.isFinite(witness)) { minOffset=Double.NaN;maxOffset=Double.NaN;return; }
        minOffset=clip(lower,witness);maxOffset=clip(upper,witness);
    }

    private double radialGap(double angle) {
        RadialRange range=range(Math.sin(angle),Math.cos(angle));
        return range==null ? Double.POSITIVE_INFINITY : range.lower()-range.upper();
    }
    private RadialRange range(double ux,double uy) {
        RadialRange a=outer.radialRange(ux,uy);
        if(a==null) return null;
        double lower=a.lower(),upper=a.upper();
        if(constraint!=null) {
            RadialRange b=constraint.radialRange(ux,uy);
            if(b==null) return null;
            lower=Math.max(lower,b.lower());upper=Math.min(upper,b.upper());
        }
        return new RadialRange(lower,upper);
    }
    private double clip(double edge,double witness) {
        if(radialGap(bearing+edge)<=1e-8) return edge;
        double feasible=witness,infeasible=edge;
        for(int i=0;i<32;i++) {
            double mid=(feasible+infeasible)*.5;
            if(radialGap(bearing+mid)<=1e-8) feasible=mid;else infeasible=mid;
        }
        return feasible;
    }
    public boolean isEmpty() { return !Double.isFinite(minOffset); }
    public Position pointAt(double angle) {
        if(constraint==null) return outer.pointAt(angle);
        double ux=Math.sin(angle),uy=Math.cos(angle);
        RadialRange range=range(ux,uy);
        if(range==null) return null;
        double lower=range.lower(),upper=range.upper();
        if(lower>upper+1e-8) return null;
        double projected=(anchor.x()-sx)*ux+(anchor.y()-sy)*uy;
        double radius=Math.max(lower,Math.min(upper,projected));
        return new Position(sx+ux*radius,sy+uy*radius,fireTime+radius/speed);
    }
}
