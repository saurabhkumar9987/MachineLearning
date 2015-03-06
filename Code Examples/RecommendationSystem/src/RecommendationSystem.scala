import java.awt.{Rectangle, ComponentOrientation}
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import smile.plot.BarPlot

import scala.swing.{MainFrame, SimpleSwingApplication}
import scala.util.Try

object RecommendationSystem extends SimpleSwingApplication {

  def top = new MainFrame {
    title = "Recommendation System Example"

    val basePath = "/Users/mikedewaard/ML_for_Hackers/03-Classification/data"
    val easyHamPath = basePath + "/easy_ham"
    println("Starting Getting mails:" + new Date(System.currentTimeMillis()))
    val mailBodies = getFilesFromDir(easyHamPath).map(x => getFullEmail(x))

    val timeSortedMails = mailBodies.map(x => (getDateFromEmail(x), getSenderFromEmail(x), getSubjectFromEmail(x), getMessageBodyFromEmail(x))).sortBy(x => x._1)

    val (trainingData, testingData) = timeSortedMails.splitAt(timeSortedMails.length / 2)
    println("Ended Getting mails:" + new Date(System.currentTimeMillis()))
    val mailsGroupedBySender = trainingData.groupBy(x => x._2).map(x => (x._1, Math.log1p(x._2.length))).toArray.sortBy(x => x._2)
    val senderDescriptions = mailsGroupedBySender.map(x => x._1)
    val senderValues = mailsGroupedBySender.map(x => x._2.toDouble)

    val mailsGroupedByThread = trainingData.groupBy(x => x._3)

    //Create a list of tuples with (subject, list of emails, time difference between first and last email)
    val mailGroupsWithMinMaxDates = mailsGroupedByThread.map(x => (x._1, x._2, (x._2.maxBy(x => x._1)._1.getTime - x._2.minBy(x => x._1)._1.getTime) / 1000))

    //turn into a list of tuples with (topic, list of emails, time difference, and weight) filtered that only threads occur
    val threadGroupsWithWeights = mailGroupsWithMinMaxDates.filter(x => x._2.length > 1).map(x => (x._1, x._2, x._3, 10 + Math.log10(x._2.length.toDouble / x._3)))

    val sendersByThread = threadGroupsWithWeights.flatMap(x => {
      x._2.groupBy(y => y._2).map(y => (y._1, y._2.length))
    })

    val groupedSenders = sendersByThread.groupBy(x => x._1).map(x => (x._1, Math.log(x._2.map(y => y._2).sum + 1) + 1))

    val stopWords = getStopWords
    val threadTermWeights = threadGroupsWithWeights.toArray.sortBy(x =>  x._4).flatMap(x => x._1.replaceAll("[^a-zA-Z ]", "").toLowerCase.split(" ").filter(_.nonEmpty).map(y => (y, x._4)))
    val filteredThreadTermWeights = threadTermWeights.groupBy(x => x._1).map(x => (x._1, x._2.maxBy(y => y._2)._2)).toArray.sortBy(x => x._1).filter(x => !stopWords.contains(x._1))

    println("Starting TDM building:" + new Date(System.currentTimeMillis()))
    val tdm = trainingData
      .flatMap(x => x._4.split(" "))
      .filter(x => x.nonEmpty && !stopWords.contains(x)).groupBy(x => x)
      .map(x => (x._1, Math.log10(x._2.length + 1))).filter(x => x._2 != 0)
    println("Finished TDM building:" + new Date(System.currentTimeMillis()))


    val threadBarPlotData = mailsGroupedByThread.map(x => (x._1, x._2.length)).toArray.sortBy(x => x._2)
    val threadDescriptions = threadBarPlotData.map(x => x._1)
    val threadValues = threadBarPlotData.map(x => Math.log1p(x._2.toDouble))

    val weightedThreadBarPlotData = threadGroupsWithWeights.toArray.sortBy(x => x._4)
    val weightedThreadDescriptions = weightedThreadBarPlotData.map(x => x._1)
    val weightedThreadValues = weightedThreadBarPlotData.map(x => x._4)


    val barPlot = BarPlot.plot("Amount of emails per subject on log scale", weightedThreadValues, weightedThreadDescriptions)
    //Rotate the email addresses by -80 degrees such that we can read them
    barPlot.getAxis(0).setRotation(-1.3962634)
    barPlot.setAxisLabel(0, "")
    barPlot.setAxisLabel(1, "Weighted amount of mails per subject ")
    peer.setContentPane(barPlot)

    bounds = new Rectangle(800, 600)


    //Given all feature data we have, it's time to clean up and merge the data to calculate
    //mailsGroupedBySender =  list(sender address, log of amount of emails sent)
    //threadGroupsWithWeights = list(Thread name, list of emails, time difference, weight)
    //filteredTermWeights = list(term, weight) for subject
    //filteredCommonTerms = list(term,weight) for email body
    println("Starting combining features:" + new Date(System.currentTimeMillis()))

    val combinedFeatures = trainingData.map(mail => {
      //mail contains (full content, date, sender, subject, body)

      //Determine the weight of the sender
      val senderWeight = mailsGroupedBySender.collectFirst { case (mail._2, x) => x}.getOrElse(1.0)

      //Determine the weight of the subject
      val termsInSubject = mail._3.replaceAll("[^a-zA-Z ]", "").toLowerCase.split(" ").filter(x => x.nonEmpty && !stopWords.contains(x) )
      val termWeight = termsInSubject.map(x => {
        tdm.collectFirst { case (y, z) if y == x => z + 1 }.getOrElse(1.0)
      }).sum / termsInSubject.length

     // val termWeight = if (calculatedTermWeight > 0) calculatedTermWeight else 1.0

      //Determine if the email is from a thread, and if it is the weight from this thread:
      val threadGroupWeight: Double = threadGroupsWithWeights.collectFirst { case (mail._3, _, _, weight) => weight}.getOrElse(1.0)

      //Determine the commonly used terms in the email and the weight belonging to it:
      val termsInMailBody = mail._4.replaceAll("[^a-zA-Z ]", "").toLowerCase.split(" ").filter(x => x.nonEmpty && !stopWords.contains(x))
      val commonTermsWeight = termsInMailBody.map(x => {
        tdm.collectFirst { case (y, z) if y == x => z + 1}.getOrElse(1.0)
      }).sum / termsInMailBody.length

     // val commonTermsWeight = if (calculatedCommonTermWeight > 0) (calculatedCommonTermWeight +1) else 1.0

      //Determine if the sender is from a possibly important thread, and if it is get the weight
      val senderThreadWeight: Double = groupedSenders.collectFirst { case (mail._2, weight) => weight}.getOrElse(1.0)

      (mail, termWeight, threadGroupWeight, commonTermsWeight, senderWeight, senderThreadWeight, (termWeight * threadGroupWeight * commonTermsWeight * senderWeight * senderThreadWeight))
    })
    println("Ended combining features:" + new Date(System.currentTimeMillis()))
   //combinedFeatures.sortBy(x => x._7).foreach(x => println("V4: " + x._7 + "\t V5: " + x._5 + "\t V6: " + x._6 + "\t V7: " + x._3 + " V8: " + x._2 + "\t V9: " + x._4 + "\t " + x._1._3 + " " + x._1._2 + x._1._1.toString))
   val sortedRankings  =  combinedFeatures.sortBy(x => x._7)
  //  sortedRankings.foreach(x => println("V4: " + x._7 + "\t " + x._1._3 + " " + x._1._2 + x._1._1.toString))

    println("Mean: " + combinedFeatures.map(x => x._7).sum / combinedFeatures.length)
    println("Median:" + sortedRankings(combinedFeatures.length/2)._7)
    val mailsInPriority = combinedFeatures.filter(x => x._7 >= 30.587070999748057)
   // combinedFeatures.sortBy(x => x._7).foreach(x => println("V4: " + x._7 + "\t " + x._1._3 + " " + x._1._2 + x._1._1.toString))
    println("Priority rated:" + mailsInPriority.length)
    sortedRankings.foreach(x => println("Rank: " + "%.5f".format(x._7) + "\t SenderWeight: " + "%.5f".format(x._5) + "\t SenderThreadWeight: " + "%.5f".format(x._6) + "\t CommonTermsWeight: " + "%.5f".format(x._3) + " SenderWeight: " + "%.5f".format(x._2) + "\t SenderThreadWeight: " + "%.5f".format(x._4) ))//+ "\t " + x._1._3 + " " + x._1._2 + x._1._1.toString))
    println("Total emails: " + combinedFeatures.length)
  }


  def getStopWords: List[String] = {
    val source = scala.io.Source.fromFile(new File("/Users/mikedewaard/MachineLearning/Example Data/stopwords.txt"))("latin1")
    val lines = source.mkString.split("\n")
    source.close()
    lines.toList
  }


  def getFilesFromDir(path: String): List[File] = {
    val d = new File(path)
    if (d.exists && d.isDirectory) {
      //Remove the mac os basic storage file, and alternatively for unix systems "cmds"
      d.listFiles.filter(x => x.isFile && !x.toString.contains(".DS_Store") && !x.toString.contains("cmds") ).toList
    } else {
      List[File]()
    }
  }


  def getFullEmail(file: File): String = {
    //Note that the encoding of the example files is latin1, thus this should be passed to the from file method.
    val source = scala.io.Source.fromFile(file)("latin1")
    val fullEmail = source.getLines mkString "\n"
    source.close()

    fullEmail
  }


  def getSubjectFromEmail(email: String): String = {

    //Find the index of the end of the subject line
    val subjectIndex = email.indexOf("Subject:")
    val endOfSubjectIndex = email.substring(subjectIndex).indexOf('\n') + subjectIndex

    //Extract the subject: start of subject + 7 (length of Subject:) until the end of the line.
    val subject = email.substring(subjectIndex + 8, endOfSubjectIndex).trim.toLowerCase

    //Additionally, we check whether the email was a response and remove the 're: ' tag, to make grouping on topic easier:
    subject.replace("re: ", "")
  }

  def getMessageBodyFromEmail(email: String): String = {

    val firstLineBreak = email.indexOf("\n\n")
    //Return the message body filtered by only text from a-z and to lower case
    email.substring(firstLineBreak).replace("\n", " ").replaceAll("[^a-zA-Z ]", "").toLowerCase
  }


  def getSenderFromEmail(email: String): String = {
    //Find the index of the From: line
    val fromLineIndex = email.indexOf("From:")
    val endOfLine = email.substring(fromLineIndex).indexOf('\n') + fromLineIndex

    //Search for the <> tags in this line, as if they are there, the email address is contained inside these tags
    val mailAddressStartIndex = email.substring(fromLineIndex, endOfLine).indexOf('<') + fromLineIndex + 1
    val mailAddressEndIndex = email.substring(fromLineIndex, endOfLine).indexOf('>') + fromLineIndex

    if (mailAddressStartIndex > mailAddressEndIndex) {

      //The email address was not embedded in <> tags, extract the substring without extra spacing and to lower case
      var emailString = email.substring(fromLineIndex + 5, endOfLine).trim.toLowerCase

      //Remove a possible name embedded in () at the end of the line, for example in test@test.com (tester) the name would be removed here
      val additionalNameStartIndex = emailString.indexOf('(')
      if (additionalNameStartIndex == -1) {
        emailString.toLowerCase
      }
      else {
        emailString.substring(0, additionalNameStartIndex).trim.toLowerCase
      }
    }
    else {
      //Extract the email address from the tags. If these <> tags are there, there is no () with a name in the From: string in our data
      email.substring(mailAddressStartIndex, mailAddressEndIndex).trim.toLowerCase
    }
  }

  def getDateFromEmail(email: String): Date = {
    //Find the index of the Date: line in the complete email
    val dateLineIndex = email.indexOf("Date:")
    val endOfDateLine = email.substring(dateLineIndex).indexOf('\n') + dateLineIndex

    //All possible date patterns in the emails.
    val datePatterns = Array("EEE MMM dd HH:mm:ss yyyy", "EEE, dd MMM yyyy HH:mm", "dd MMM yyyy HH:mm:ss", "EEE MMM dd yyyy HH:mm")

    datePatterns.foreach { x =>
      //Try to directly return a date from the formatting.when it fails on a pattern it continues with the next one until one works
      Try(return new SimpleDateFormat(x).parse(email.substring(dateLineIndex + 5, endOfDateLine).trim.substring(0, x.length)))
    }
    //Finally, if all failed return null (this will not happen with our example data but without this return the code will not compile)
    null
  }
}
