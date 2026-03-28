# Saguaro

A Robocode bot by Oscar Gilbert. Results can be viewed on [literumble](https://literumble.appspot.com/Rankings?game=roborumble).

## Description

Saguaro is a multimode bot. It currently has 5 different modes, with more modes planned in the future. The modes are:

1. ScoreMax: This is the bot's main mode. It works by generating a number of candidate combinations of movement paths/targeting angles/bullet powers and then trying to directly estimate each plan's expected score and selecting the best plan.
2. BulletShielding: A BulletShielding mode that tries to incorporate shots aimed to hit the opponent as well whenever it is able to find enough spacing between shielding shots to work them in.
3. MovingBulletShielding: Similar to the above mode, but it is allowed to reposition itself when shots are not active
4. PerfectPrediction: A mode designed to get near-100% accuracy against bots whose movement is a predictable function of the current state. Currently only used for bots that mirror our movement and bots that ram us.
5. ShotDodger: A mode designed to exploit bots with very predictable targeting. It models their firing angles very precisely in this mode and learns which strategy our opponent is using to aim from a library of common targeting strategies.

## Acknowledgements
This robot was based on lessons learned from countless other robocode bot authors, so a big thanks to every author who has made their bots publicly available. Special thanks to:
* Rednaxela - this bot uses his kd-tree implementation as well as his FastTrig class
* Kev and Skilgannon: this bot uses several things from kc.mega.BeepBoop 2.0 and jk.precise.EnergyDome 1.6. In particular the bullet shielding is a combination of how EnergyDome learns opponent shot angles along with BeepBoop's geometry for intercepting the incoming bullet, and the methods for calculating precise 50%-weighted bullet shadows and cheaper precise MEAs are both based on how BeepBoop does them.