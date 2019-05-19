package session

import database.DatabaseHandler
import memscan.MemHandler
import memscan.PlayerData
import memscan.XrdApi
import tornadofx.Controller
import utils.Duo
import kotlin.math.max


class Session: Controller() {
    val xrdApi: XrdApi = MemHandler()
    val dataApi: DatabaseHandler = DatabaseHandler("","","")
    val players: HashMap<Long, Player> = HashMap()
    val matches: HashMap<Long, Match> = HashMap()
    var match = Match(0, 0x0)

    fun updatePlayers(): Boolean {
        var somethingChanged = false
        var bountyReward = 0
        val playerData = xrdApi.getPlayerData()

        playerData.forEach { data ->
            if (data.steamUserId != 0L) {
                // Add player if they aren't already stored
                if (!players.containsKey(data.steamUserId)) {
                    players[data.steamUserId] = Player(data); somethingChanged = true }

                // The present is now the past, and the future is now the present
                val player = players[data.steamUserId]!!
                if (!player.getData().equals(data)) { somethingChanged = true }
                player.updatePlayerData(data, getActivePlayerCount())

                // Resolve if a game occured and what the reward will be
                val bountyLost = resolveEveryoneElse(data)
                if (bountyLost > 0) { bountyReward = bountyLost; somethingChanged = true }
            }
        }

        // Pay the winner
        playerData.forEach { resolveTheWinner(it, bountyReward) }

        // New match underway?
        val matchPending = Duo(PlayerData(), PlayerData())
        playerData.forEach { data ->
            if (data.loadingPct > 0 &&
                data.loadingPct < 100
            ) {
                if (data.cabinetLoc.toInt().equals(0) && data.playerSide.toInt().equals(0)) matchPending.p1 = data
                if (data.cabinetLoc.toInt().equals(0) && data.playerSide.toInt().equals(1)) matchPending.p2 = data
            }
        }
        if (match.matchId != matches.size.toLong() && matchPending.p1.steamUserId > 0L && matchPending.p2.steamUserId > 0L) {
            match = Match(matches.size.toLong(), 0x0, matchPending)
            println("SESSION: EMPTY MATCH GENERATED WITH ID ${match.matchId}")
        }

        return somethingChanged
    }

    fun updateMatch(): Boolean {
        val matchData = xrdApi.getMatchData()
        val updated = match.updateMatchData(matchData)
        return updated
    }

    private fun resolveTheWinner(data: PlayerData, loserChange: Int) {
        players.values.filter { it.getSteamId() == data.steamUserId && it.hasWon() }.forEach { w ->
            // Archive the completed match
            if (match.getWinner() > -1 ) {
                matches.put(match.matchId, match)
                println("SESSION: MATCH ID ${match.matchId} ARCHIVED AT INDEX ${matches.size-1}")
            }

            w.changeChain(1)
            val payout = w.getChain() * w.getMatchesWon() + w.getMatchesPlayed() + loserChange + (w.getChain() * w.getChain() * 100)
            w.changeBounty(payout)
        }
    }

    private fun resolveEveryoneElse(data: PlayerData): Int {
        var loserChange = 0
        players.values.filter { it.getSteamId().equals(data.steamUserId) && it.hasLost() }.forEach { l ->
            players.values.forEach { p -> if (!p.hasPlayed()) p.incrementIdle(getActivePlayerCount()) }
            l.changeChain(-1)
            if (l.getBounty() > 0) loserChange = l.getBounty().div(3)
            l.changeBounty(-loserChange)
            return loserChange
        }
        return 0
    }

    fun getActivePlayerCount() = max(players.values.filter { !it.isIdle() }.size, 1)

    fun isMatchVisible(): Boolean = match.isMatchOngoing()

}
